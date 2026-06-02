package br.com.rinha.fraude;

import java.io.FileDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Recebe file descriptors de conexões TCP de clientes, passados pelo load
 * balancer via SCM_RIGHTS num Unix control socket. Cada fd recebido é envolvido
 * num {@link SocketChannel} e entregue ao consumidor (que o injeta no event
 * loop). O LB sai do data path: a API lê/escreve direto no socket do cliente.
 *
 * Linux-only (depende de recvmsg/SCM_RIGHTS e do construtor interno
 * sun.nio.ch.SocketChannelImpl). Em outros SOs, falha no boot — mas a Rinha é
 * Linux.
 */
final class FdReceiver {

    // Construtor interno do JDK que envolve um fd cru num SocketChannel sem
    // passar pelo accept() do SO. Assinatura do JDK 25:
    //   SocketChannelImpl(SelectorProvider, ProtocolFamily, FileDescriptor, SocketAddress)
    private static final MethodHandle SOCKET_CHANNEL_CTOR;
    private static final VarHandle FD_FIELD;

    static {
        try {
            Class<?> impl = Class.forName("sun.nio.ch.SocketChannelImpl");
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(impl, MethodHandles.lookup());
            SOCKET_CHANNEL_CTOR = lk.findConstructor(impl, MethodType.methodType(
                    void.class, SelectorProvider.class, ProtocolFamily.class,
                    FileDescriptor.class, java.net.SocketAddress.class));
            FD_FIELD = MethodHandles.privateLookupIn(FileDescriptor.class, MethodHandles.lookup())
                    .findVarHandle(FileDescriptor.class, "fd", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String controlSocketPath;
    private final Consumer<SocketChannel> onChannel;
    private volatile boolean running = true;

    FdReceiver(String controlSocketPath, Consumer<SocketChannel> onChannel) {
        this.controlSocketPath = controlSocketPath;
        this.onChannel = onChannel;
    }

    // SO_BUSY_POLL em µs, configurável via env. 0 = desativado (default). Permite
    // ligar/desligar e medir o efeito sem rebuildar.
    private static final int BUSY_POLL_US = parseBusyPoll();

    private static int parseBusyPoll() {
        String v = System.getenv("RINHA_BUSY_POLL_US");
        if (v == null || v.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Envolve um fd cru de socket TCP num SocketChannel não-bloqueante. */
    static SocketChannel wrapFd(int rawFd) throws Throwable {
        if (BUSY_POLL_US > 0) {
            FdPassing.enableBusyPoll(rawFd, BUSY_POLL_US); // best-effort; ignora falha
        }
        FileDescriptor jfd = new FileDescriptor();
        FD_FIELD.set(jfd, rawFd);
        SocketChannel ch = (SocketChannel) SOCKET_CHANNEL_CTOR.invoke(
                SelectorProvider.provider(), StandardProtocolFamily.INET, jfd, null);
        ch.configureBlocking(false);
        return ch;
    }

    /** Inicia a thread que escuta o control socket e recebe fds. */
    void start() throws Exception {
        Path path = Path.of(controlSocketPath);
        Files.deleteIfExists(path);
        Files.createDirectories(path.getParent());
        ServerSocketChannel ctrlServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ctrlServer.bind(UnixDomainSocketAddress.of(path), 128);
        try {
            Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (Exception ignored) {
        }

        Thread t = Thread.ofPlatform().name("rinha-fd-receiver").daemon(false).start(() -> acceptLoop(ctrlServer));
        // Mantém referência viva via thread; nada mais a fazer aqui.
        if (t == null) throw new IllegalStateException("falha ao iniciar fd-receiver");
    }

    void stop() {
        running = false;
    }

    private void acceptLoop(ServerSocketChannel ctrlServer) {
        // O LB abre conexões de controle persistentes (uma ou poucas). Para cada
        // conexão de controle aceita, fica num loop recvmsg recebendo fds.
        try (ctrlServer) {
            while (running) {
                SocketChannel ctrl = ctrlServer.accept();
                if (ctrl == null) continue;
                // Uma thread por conexão de controle. Normalmente o LB mantém 1
                // conexão persistente por API, então é 1 thread leve.
                final SocketChannel ctrlConn = ctrl;
                Thread.ofPlatform().name("rinha-fd-ctrl").daemon(true).start(() -> receiveLoop(ctrlConn));
            }
        } catch (Exception ex) {
            if (running) System.err.println("FdReceiver acceptLoop: " + ex.getMessage());
        }
    }

    private void receiveLoop(SocketChannel ctrlConn) {
        int ctrlFd = rawFdOf(ctrlConn);
        if (ctrlFd < 0) {
            try { ctrlConn.close(); } catch (Exception ignored) {}
            return;
        }
        try {
            while (running) {
                int clientFd = FdPassing.receive(ctrlFd);
                if (clientFd < 0) break; // control connection fechada
                try {
                    SocketChannel client = wrapFd(clientFd);
                    onChannel.accept(client);
                } catch (Throwable t) {
                    // Não conseguiu envolver o fd — fecha pra não vazar.
                    closeRawFd(clientFd);
                }
            }
        } finally {
            try { ctrlConn.close(); } catch (Exception ignored) {}
        }
    }

    /** Extrai o fd cru de um SocketChannel (para passar ao recvmsg). */
    static int rawFdOf(SocketChannel ch) {
        try {
            // SocketChannelImpl tem um FileDescriptor 'fd'; lemos o int via reflection.
            java.lang.reflect.Field fdField = ch.getClass().getDeclaredField("fd");
            fdField.setAccessible(true);
            FileDescriptor jfd = (FileDescriptor) fdField.get(ch);
            return (int) FD_FIELD.get(jfd);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void closeRawFd(int fd) {
        try {
            SocketChannel ch = wrapFd(fd);
            ch.close();
        } catch (Throwable ignored) {
        }
    }
}
