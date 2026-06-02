package br.com.rinha.fraude;

import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Valida o FD passing (FFM recvmsg/sendmsg + wrap de fd) isoladamente, sem o LB
 * Rust. Monta um canal de controle UDS (duas pontas conectadas), cria um socket
 * "carga", passa o fd dele de uma ponta e recebe da outra, confirmando que o fd
 * recebido refere o mesmo socket e funciona.
 */
final class FdSelfTest {

    private FdSelfTest() {
    }

    static void run() throws Throwable {
        Path ctrlPath = Path.of("/tmp/rinha-fdselftest-ctrl.sock");
        Files.deleteIfExists(ctrlPath);

        // 1) Canal de controle: server UDS + cliente conectado = duas pontas.
        try (ServerSocketChannel ctrlServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            ctrlServer.bind(UnixDomainSocketAddress.of(ctrlPath));

            SocketChannel ctrlClient = SocketChannel.open(UnixDomainSocketAddress.of(ctrlPath));
            SocketChannel ctrlAccepted = ctrlServer.accept();

            int sendFd = FdReceiver.rawFdOf(ctrlClient);
            int recvFd = FdReceiver.rawFdOf(ctrlAccepted);
            check("rawFd do ctrlClient", sendFd >= 0);
            check("rawFd do ctrlAccepted", recvFd >= 0);

            // 2) Socket "carga" cujo fd será passado: abrimos um segundo UDS server
            //    e conectamos um cliente; passamos o fd do cliente.
            Path payloadPath = Path.of("/tmp/rinha-fdselftest-payload.sock");
            Files.deleteIfExists(payloadPath);
            try (ServerSocketChannel payloadServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                payloadServer.bind(UnixDomainSocketAddress.of(payloadPath));
                SocketChannel payloadClient = SocketChannel.open(UnixDomainSocketAddress.of(payloadPath));
                SocketChannel payloadAccepted = payloadServer.accept();

                int payloadFd = FdReceiver.rawFdOf(payloadClient);
                check("rawFd do payloadClient", payloadFd >= 0);

                // 3) Envia o fd da carga por uma ponta do controle.
                boolean sent = FdPassing.send(sendFd, payloadFd);
                check("send() retornou sucesso", sent);

                // 4) Recebe da outra ponta.
                int received = FdPassing.receive(recvFd);
                check("receive() retornou fd valido (>=0)", received >= 0);
                check("fd recebido != stdin(0)", received != 0);

                // 5) Envolve o fd recebido num SocketChannel e escreve nele;
                //    o payloadAccepted (outro lado) deve ler os bytes.
                SocketChannel wrapped = FdReceiver.wrapFd(received);
                wrapped.configureBlocking(true);
                byte[] msg = "PING".getBytes();
                wrapped.write(java.nio.ByteBuffer.wrap(msg));

                java.nio.ByteBuffer rb = java.nio.ByteBuffer.allocate(16);
                payloadAccepted.configureBlocking(true);
                int n = payloadAccepted.read(rb);
                rb.flip();
                byte[] got = new byte[n];
                rb.get(got);
                check("bytes trafegam pelo fd recebido", new String(got).equals("PING"));

                wrapped.close();
                payloadClient.close();
                payloadAccepted.close();
            }
            ctrlClient.close();
            ctrlAccepted.close();
            Files.deleteIfExists(payloadPath);
        }
        Files.deleteIfExists(ctrlPath);
        System.out.println("SELFTEST-FD: todos os checks passaram — FD passing funcional ✓");
    }

    private static void check(String desc, boolean cond) {
        if (!cond) {
            System.out.println("SELFTEST-FD FALHOU: " + desc);
            throw new AssertionError(desc);
        }
        System.out.println("  ok: " + desc);
    }
}
