package br.com.rinha.fraude;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Primitivas de FD passing (SCM_RIGHTS) via Foreign Function & Memory API.
 *
 * O load balancer aceita a conexão TCP do cliente e passa o file descriptor
 * dela para uma das APIs por um Unix control socket. A API lê/escreve direto no
 * socket do cliente — o LB sai do data path após o handoff. Elimina o
 * proxying de bytes que o nginx fazia por request.
 *
 * Layouts nativos para Linux x86-64 (glibc 64-bit). Os offsets vêm do ABI:
 *   struct msghdr  (56 bytes): msg_name(8) msg_namelen(4)+pad(4) msg_iov(8)
 *                  msg_iovlen(8) msg_control(8) msg_controllen(8) msg_flags(4)+pad(4)
 *   struct iovec   (16 bytes): iov_base(8) iov_len(8)
 *   struct cmsghdr (16 bytes): cmsg_len(size_t=8) cmsg_level(int=4) cmsg_type(int=4)
 *                  seguido do dado (fd int32) em offset 16.
 *   CMSG_SPACE(sizeof(int)) = align8(16+4) = 24.  CMSG_LEN(4) = 16+4 = 20.
 */
final class FdPassing {

    private static final int SOL_SOCKET = 1;
    private static final int SCM_RIGHTS = 1;

    private static final long MSGHDR_SIZE = 56;
    private static final long OFF_MSG_IOV = 16;
    private static final long OFF_MSG_IOVLEN = 24;
    private static final long OFF_MSG_CONTROL = 32;
    private static final long OFF_MSG_CONTROLLEN = 40;

    private static final long IOVEC_SIZE = 16;
    private static final long OFF_IOV_BASE = 0;
    private static final long OFF_IOV_LEN = 8;

    private static final long CMSG_SPACE = 24;
    private static final long OFF_CMSG_LEN = 0;
    private static final long OFF_CMSG_LEVEL = 8;
    private static final long OFF_CMSG_TYPE = 12;
    private static final long OFF_CMSG_DATA = 16;
    private static final long CMSG_LEN_VALUE = 20;

    private static final MethodHandle RECVMSG;
    private static final MethodHandle SENDMSG;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        FunctionDescriptor desc = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,   // ssize_t
                ValueLayout.JAVA_INT,    // int sockfd
                ValueLayout.ADDRESS,     // struct msghdr*
                ValueLayout.JAVA_INT);   // int flags
        RECVMSG = linker.downcallHandle(
                lookup.find("recvmsg").orElseThrow(() -> new RuntimeException("recvmsg ausente")), desc);
        SENDMSG = linker.downcallHandle(
                lookup.find("sendmsg").orElseThrow(() -> new RuntimeException("sendmsg ausente")), desc);
    }

    /** Buffers nativos por thread, reusados — zero alocação por chamada. */
    private static final ThreadLocal<Buffers> BUFS = ThreadLocal.withInitial(Buffers::new);

    private static final class Buffers {
        final MemorySegment msghdr;
        final MemorySegment iovec;
        final MemorySegment dataByte;
        final MemorySegment cmsgBuf;

        Buffers() {
            Arena arena = Arena.ofConfined();
            msghdr = arena.allocate(MSGHDR_SIZE, 8);
            iovec = arena.allocate(IOVEC_SIZE, 8);
            dataByte = arena.allocate(1, 1);
            cmsgBuf = arena.allocate(CMSG_SPACE, 8);

            iovec.set(ValueLayout.ADDRESS, OFF_IOV_BASE, dataByte);
            iovec.set(ValueLayout.JAVA_LONG, OFF_IOV_LEN, 1L);

            msghdr.set(ValueLayout.ADDRESS, OFF_MSG_IOV, iovec);
            msghdr.set(ValueLayout.JAVA_LONG, OFF_MSG_IOVLEN, 1L);
            msghdr.set(ValueLayout.ADDRESS, OFF_MSG_CONTROL, cmsgBuf);
            msghdr.set(ValueLayout.JAVA_LONG, OFF_MSG_CONTROLLEN, CMSG_SPACE);

            cmsgBuf.set(ValueLayout.JAVA_LONG, OFF_CMSG_LEN, CMSG_LEN_VALUE);
            cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_LEVEL, SOL_SOCKET);
            cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_TYPE, SCM_RIGHTS);
        }
    }

    private FdPassing() {
    }

    /**
     * Recebe um file descriptor de um control socket via recvmsg(SCM_RIGHTS).
     * @return o fd recebido (>=0), ou -1 em erro/conexão fechada.
     */
    static int receive(int controlSocketFd) {
        Buffers b = BUFS.get();
        b.dataByte.set(ValueLayout.JAVA_BYTE, 0, (byte) 0);
        b.cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_DATA, 0);
        // O kernel reescreve msg_controllen com o tamanho real após recvmsg;
        // restaurar para a capacidade alocada antes de cada chamada.
        b.msghdr.set(ValueLayout.JAVA_LONG, OFF_MSG_CONTROLLEN, CMSG_SPACE);

        long received;
        try {
            received = (long) RECVMSG.invokeExact(controlSocketFd, b.msghdr, 0);
        } catch (Throwable t) {
            return -1;
        }
        if (received <= 0) return -1;

        // Se recvmsg retornou dados mas sem payload SCM_RIGHTS, msg_controllen
        // fica < CMSG_LEN e os campos do cmsghdr são lixo do construtor —
        // ler o fd retornaria 0 (stdin) silenciosamente. Validar antes.
        long actualCtrlLen = b.msghdr.get(ValueLayout.JAVA_LONG, OFF_MSG_CONTROLLEN);
        if (actualCtrlLen < CMSG_LEN_VALUE) return -1;

        long cmsgLen = b.cmsgBuf.get(ValueLayout.JAVA_LONG, OFF_CMSG_LEN);
        int cmsgLevel = b.cmsgBuf.get(ValueLayout.JAVA_INT, OFF_CMSG_LEVEL);
        int cmsgType = b.cmsgBuf.get(ValueLayout.JAVA_INT, OFF_CMSG_TYPE);
        if (cmsgLen < CMSG_LEN_VALUE || cmsgLevel != SOL_SOCKET || cmsgType != SCM_RIGHTS) {
            return -1;
        }
        return b.cmsgBuf.get(ValueLayout.JAVA_INT, OFF_CMSG_DATA);
    }

    /**
     * Envia um fd por um control socket conectado. Usado pelo self-test
     * (em produção o sender é o LB em Rust).
     * @return true se enviou 1 byte com o fd anexado.
     */
    static boolean send(int controlSocketFd, int fdToSend) {
        Buffers b = BUFS.get();
        b.dataByte.set(ValueLayout.JAVA_BYTE, 0, (byte) 0);
        b.cmsgBuf.set(ValueLayout.JAVA_LONG, OFF_CMSG_LEN, CMSG_LEN_VALUE);
        b.cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_LEVEL, SOL_SOCKET);
        b.cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_TYPE, SCM_RIGHTS);
        b.cmsgBuf.set(ValueLayout.JAVA_INT, OFF_CMSG_DATA, fdToSend);
        b.msghdr.set(ValueLayout.JAVA_LONG, OFF_MSG_CONTROLLEN, CMSG_SPACE);

        try {
            return (long) SENDMSG.invokeExact(controlSocketFd, b.msghdr, 0) == 1L;
        } catch (Throwable t) {
            return false;
        }
    }
}
