//! Load balancer L4 com FD passing (SCM_RIGHTS) para a Rinha de Backend 2026.
//!
//! Escuta TCP em :9999. Para cada conexão de cliente aceita, passa o file
//! descriptor cru para uma das APIs (round-robin) por um Unix control socket
//! usando sendmsg/SCM_RIGHTS. A API lê/escreve direto no socket do cliente; o
//! LB sai do data path após o handoff (não copia bytes por request).
//!
//! Linux x86-64. Sem runtime async — accept bloqueante single-thread basta: o
//! único trabalho por conexão é um sendmsg + close, microssegundos.

use std::env;
use std::mem;
use std::os::unix::io::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::ptr;
use std::thread;
use std::time::Duration;

const N_BACKENDS: usize = 2;

fn main() {
    // FD_UPSTREAMS: caminhos dos control sockets das APIs, separados por vírgula.
    // Ex: /sockets/api1-fd.sock,/sockets/api2-fd.sock
    let upstreams_env = env::var("FD_UPSTREAMS")
        .unwrap_or_else(|_| "/sockets/api1-fd.sock,/sockets/api2-fd.sock".to_string());
    let upstream_paths: Vec<String> = upstreams_env.split(',').map(|s| s.trim().to_string()).collect();
    assert_eq!(upstream_paths.len(), N_BACKENDS, "esperado {} upstreams", N_BACKENDS);

    let listen_addr = env::var("LISTEN_ADDR").unwrap_or_else(|_| "0.0.0.0:9999".to_string());

    // Conexões de controle persistentes para cada API (com retry até subirem).
    let mut ctrl: Vec<UnixStream> = Vec::with_capacity(N_BACKENDS);
    for path in &upstream_paths {
        ctrl.push(connect_with_retry(path));
    }
    eprintln!("[lb] conectado aos {} control sockets", N_BACKENDS);

    // Socket TCP de escuta.
    let listener = bind_listener(&listen_addr);
    eprintln!("[lb] escutando TCP em {}", listen_addr);

    let mut next: usize = 0;
    loop {
        let client_fd = unsafe { libc::accept(listener, ptr::null_mut(), ptr::null_mut()) };
        if client_fd < 0 {
            continue;
        }
        // TCP_NODELAY no socket do cliente (a primeira resposta não espera Nagle).
        set_tcp_nodelay(client_fd);

        // Round-robin entre as APIs; se o send falhar, tenta a outra.
        let mut delivered = false;
        for attempt in 0..N_BACKENDS {
            let idx = (next + attempt) % N_BACKENDS;
            let ctrl_fd = ctrl[idx].as_raw_fd();
            if send_fd(ctrl_fd, client_fd) {
                delivered = true;
                next = (idx + 1) % N_BACKENDS;
                break;
            }
            // Control socket caiu — reconecta e tenta de novo.
            eprintln!("[lb] control socket {} falhou, reconectando", idx);
            ctrl[idx] = connect_with_retry(&upstream_paths[idx]);
        }
        if !delivered {
            eprintln!("[lb] nenhuma API aceitou o fd; descartando conexão");
        }
        // O fd foi duplicado pelo kernel no recvmsg da API; fechamos a cópia local.
        unsafe { libc::close(client_fd) };
    }
}

fn bind_listener(addr: &str) -> RawFd {
    let parts: Vec<&str> = addr.rsplitn(2, ':').collect();
    let port: u16 = parts[0].parse().expect("porta inválida");

    let fd = unsafe { libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0) };
    assert!(fd >= 0, "socket() falhou");

    let one: libc::c_int = 1;
    unsafe {
        libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_REUSEADDR,
            &one as *const libc::c_int as *const libc::c_void, mem::size_of_val(&one) as libc::socklen_t);
        // TCP_DEFER_ACCEPT: só acorda no primeiro byte de dados (a request HTTP).
        libc::setsockopt(fd, libc::IPPROTO_TCP, libc::TCP_DEFER_ACCEPT,
            &one as *const libc::c_int as *const libc::c_void, mem::size_of_val(&one) as libc::socklen_t);
    }

    let mut sin: libc::sockaddr_in = unsafe { mem::zeroed() };
    sin.sin_family = libc::AF_INET as libc::sa_family_t;
    sin.sin_port = port.to_be();
    sin.sin_addr.s_addr = libc::INADDR_ANY.to_be();
    let rc = unsafe {
        libc::bind(fd, &sin as *const libc::sockaddr_in as *const libc::sockaddr, mem::size_of::<libc::sockaddr_in>() as libc::socklen_t)
    };
    assert!(rc == 0, "bind() falhou");
    let rc = unsafe { libc::listen(fd, 1024) };
    assert!(rc == 0, "listen() falhou");
    fd
}

fn connect_with_retry(path: &str) -> UnixStream {
    loop {
        match UnixStream::connect(path) {
            Ok(s) => return s,
            Err(_) => thread::sleep(Duration::from_millis(100)),
        }
    }
}

fn set_tcp_nodelay(fd: RawFd) {
    let one: libc::c_int = 1;
    unsafe {
        libc::setsockopt(fd, libc::IPPROTO_TCP, libc::TCP_NODELAY,
            &one as *const libc::c_int as *const libc::c_void, mem::size_of_val(&one) as libc::socklen_t);
    }
}

/// Envia um fd por um Unix socket conectado via sendmsg(SCM_RIGHTS).
/// Layout idêntico ao que o FdPassing.java (lado Java) espera receber.
fn send_fd(ctrl_fd: RawFd, fd_to_send: RawFd) -> bool {
    // 1 byte de payload (recvmsg precisa de pelo menos 1 byte de dados normais).
    let mut data: [u8; 1] = [0];
    let mut iov = libc::iovec {
        iov_base: data.as_mut_ptr().cast(),
        iov_len: 1,
    };

    // Buffer de controle: CMSG_SPACE(sizeof(int)) = 24 bytes em glibc x86-64.
    const CMSG_BUF_LEN: usize = 24;
    let mut cmsg_buf = [0u8; CMSG_BUF_LEN];

    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast();
    msg.msg_controllen = CMSG_BUF_LEN;

    unsafe {
        let cmsg = libc::CMSG_FIRSTHDR(&msg);
        if cmsg.is_null() {
            return false;
        }
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        // CMSG_LEN(sizeof(int)) = 16 + 4 = 20.
        (*cmsg).cmsg_len = libc::CMSG_LEN(mem::size_of::<libc::c_int>() as u32) as usize;
        let data_ptr = libc::CMSG_DATA(cmsg).cast::<libc::c_int>();
        ptr::write(data_ptr, fd_to_send);

        let sent = libc::sendmsg(ctrl_fd, &msg, 0);
        sent == 1
    }
}
