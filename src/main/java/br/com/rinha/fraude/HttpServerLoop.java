package br.com.rinha.fraude;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Servidor HTTP/1.1 NIO. Suporta TCP (porta) ou Unix Domain Socket (caminho).
 *
 * Arquitetura: 1 thread de accept + N workers. A thread de accept aceita
 * conexões no ServerSocketChannel e distribui round-robin entre os workers.
 * Cada worker tem seu próprio Selector e processa suas conexões em paralelo,
 * usando a CPU que de outra forma ficaria ociosa enquanto uma thread espera
 * I/O. O DetectionEngine (Vectorizer + IvfIndex mmap) é read-only e pode ser
 * compartilhado entre workers sem sincronização. Cada Connection tem buffers,
 * parser e scratch próprios — zero estado compartilhado mutável no hot path.
 *
 * Atendemos exatamente dois endpoints:
 *   GET /ready        -> 200 "ready" (ou 503 enquanto warmup não completa)
 *   POST /fraud-score -> 200 application/json (uma de 6 respostas pré-encodadas)
 *
 * Parser HTTP mínimo: Content-Length obrigatório, sem chunked/upgrades/trailers.
 */
final class HttpServerLoop implements Runnable {

  private static final byte[] READY_RESPONSE = ("HTTP/1.1 200 OK\r\n"
      + "Content-Type: text/plain\r\n"
      + "Content-Length: 5\r\n"
      + "Connection: keep-alive\r\n"
      + "\r\n"
      + "ready").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static final byte[] NOT_READY_RESPONSE = ("HTTP/1.1 503 Service Unavailable\r\n"
      + "Content-Type: text/plain\r\n"
      + "Content-Length: 9\r\n"
      + "Connection: keep-alive\r\n"
      + "Retry-After: 1\r\n"
      + "\r\n"
      + "not-ready").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static final byte[] METHOD_NOT_ALLOWED = ("HTTP/1.1 405 Method Not Allowed\r\n"
      + "Content-Length: 0\r\n"
      + "Connection: close\r\n"
      + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static final byte[] NOT_FOUND = ("HTTP/1.1 404 Not Found\r\n"
      + "Content-Length: 0\r\n"
      + "Connection: close\r\n"
      + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static final byte[][] FRAUD_RESPONSES = buildFraudResponses();

  private static final int MAX_HEADER_SIZE = 4096;
  private static final int MAX_BODY_SIZE = 8192;
  private static final int CONNECTION_BUFFER_SIZE = MAX_HEADER_SIZE + MAX_BODY_SIZE;

  private final SocketAddress bindAddress;
  private final ProtocolFamily protocolFamily;
  private final Path udsPathToCleanup;
  private final DetectionEngine engine;
  private final int workerCount;
  private volatile boolean running = true;
  private volatile boolean ready = false;

  private Worker[] workers;
  private Thread[] workerThreads;

  HttpServerLoop(SocketAddress bindAddress, ProtocolFamily protocolFamily, Path udsPathToCleanup,
      DetectionEngine engine, int workerCount) {
    this.bindAddress = bindAddress;
    this.protocolFamily = protocolFamily;
    this.udsPathToCleanup = udsPathToCleanup;
    this.engine = engine;
    this.workerCount = Math.max(1, workerCount);
  }

  void stop() {
    running = false;
    if (workers != null) {
      for (Worker w : workers) {
        if (w != null) w.wakeup();
      }
    }
  }

  void markReady() {
    ready = true;
  }

  boolean isReady() {
    return ready;
  }

  @Override
  public void run() {
    try (ServerSocketChannel server = ServerSocketChannel.open(protocolFamily)) {
      if (protocolFamily == StandardProtocolFamily.INET && bindAddress instanceof InetSocketAddress) {
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      }
      server.bind(bindAddress, 1024);
      server.configureBlocking(true); // accept bloqueante na thread dedicada

      if (udsPathToCleanup != null) {
        try {
          Files.setPosixFilePermissions(udsPathToCleanup,
              java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (Exception ignored) {
        }
      }

      // Inicia os workers.
      workers = new Worker[workerCount];
      workerThreads = new Thread[workerCount];
      for (int i = 0; i < workerCount; i++) {
        workers[i] = new Worker(engine, this);
        workerThreads[i] = Thread.ofPlatform()
            .name("rinha-worker-" + i)
            .daemon(false)
            .start(workers[i]);
      }

      // Loop de accept: distribui round-robin entre os workers.
      int next = 0;
      while (running) {
        SocketChannel client;
        try {
          client = server.accept();
        } catch (IOException io) {
          if (!running) break;
          continue;
        }
        if (client == null) continue;
        client.configureBlocking(false);
        if (protocolFamily == StandardProtocolFamily.INET) {
          client.setOption(StandardSocketOptions.TCP_NODELAY, true);
          client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        }
        workers[next].enqueue(client);
        next++;
        if (next == workerCount) next = 0;
      }
    } catch (IOException ex) {
      System.err.println("HttpServerLoop encerrando: " + ex.getMessage());
    } finally {
      if (workers != null) {
        for (Worker w : workers) {
          if (w != null) w.wakeup();
        }
      }
      if (udsPathToCleanup != null) {
        try {
          Files.deleteIfExists(udsPathToCleanup);
        } catch (IOException ignored) {
        }
      }
    }
  }

  /**
   * Worker com Selector próprio. Recebe novas conexões via fila concorrente
   * (preenchida pela thread de accept) e as registra no seu Selector. Processa
   * leitura/escrita das conexões sequencialmente dentro da sua thread — sem
   * contenção com outros workers, exceto pela leitura read-only do índice.
   */
  private static final class Worker implements Runnable {
    private final DetectionEngine engine;
    private final HttpServerLoop server;
    private final Selector selector;
    private final Queue<SocketChannel> pending = new ConcurrentLinkedQueue<>();

    Worker(DetectionEngine engine, HttpServerLoop server) throws IOException {
      this.engine = engine;
      this.server = server;
      this.selector = Selector.open();
    }

    void enqueue(SocketChannel channel) {
      pending.add(channel);
      selector.wakeup();
    }

    void wakeup() {
      selector.wakeup();
    }

    @Override
    public void run() {
      try (selector) {
        while (server.running) {
          selector.select(1000L);

          // Registra conexões novas enfileiradas pela thread de accept.
          SocketChannel ch;
          while ((ch = pending.poll()) != null) {
            try {
              Connection connection = new Connection(ch, engine, server);
              ch.register(selector, SelectionKey.OP_READ, connection);
            } catch (IOException io) {
              try { ch.close(); } catch (IOException ignored) {}
            }
          }

          Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
          while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            if (!key.isValid()) continue;
            try {
              if (key.isReadable()) {
                ((Connection) key.attachment()).onReadable(key);
              }
            } catch (IOException io) {
              closeQuietly(key);
            }
          }
        }
      } catch (IOException ex) {
        System.err.println("Worker encerrando: " + ex.getMessage());
      }
    }

    private static void closeQuietly(SelectionKey key) {
      try {
        key.channel().close();
      } catch (IOException ignored) {
      }
      key.cancel();
    }
  }

  /**
   * Estado de uma conexão. Aloca uma única vez (no accept), reaproveita buffers,
   * parser e scratch durante toda a vida da conexão. Confinada a um único worker
   * — nunca acessada por mais de uma thread.
   */
  private static final class Connection {
    private final SocketChannel channel;
    private final DetectionEngine engine;
    private final HttpServerLoop server;
    private final JsonRequestParser parser = new JsonRequestParser();
    private final MutableTransactionRequest request = new MutableTransactionRequest();
    private final float[] query = new float[Vectorizer.PADDED_DIMENSIONS];
    private final SearchScratch scratch;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(CONNECTION_BUFFER_SIZE);
    private final ByteBuffer writeBuffer = ByteBuffer.allocate(256);

    Connection(SocketChannel channel, DetectionEngine engine, HttpServerLoop server) {
      this.channel = channel;
      this.engine = engine;
      this.server = server;
      this.scratch = new SearchScratch(engine.index().clusterCount, engine.index().refineProbes);
    }

    void onReadable(SelectionKey key) throws IOException {
      int read = channel.read(readBuffer);
      if (read < 0) {
        closeQuietly(key);
        return;
      }
      processBuffered(key);
    }

    private void processBuffered(SelectionKey key) throws IOException {
      while (true) {
        readBuffer.flip();
        int headerEnd = findHeaderEnd(readBuffer);
        if (headerEnd < 0) {
          readBuffer.compact();
          return;
        }
        int methodLen = scanMethod(readBuffer);
        if (methodLen < 0) {
          sendAndClose(key, METHOD_NOT_ALLOWED);
          return;
        }
        int pathStart = methodLen + 1;
        int pathEnd = scanPathEnd(readBuffer, pathStart);
        if (pathEnd < 0) {
          sendAndClose(key, METHOD_NOT_ALLOWED);
          return;
        }
        int contentLength = parseContentLength(readBuffer, headerEnd);
        int totalLen = headerEnd + contentLength;
        if (readBuffer.limit() < totalLen) {
          readBuffer.compact();
          return;
        }
        boolean handled = dispatch(methodLen, pathStart, pathEnd, headerEnd, contentLength);
        if (!handled) {
          sendAndClose(key, NOT_FOUND);
          return;
        }
        readBuffer.position(totalLen);
        readBuffer.compact();
        if (readBuffer.position() == 0) {
          return;
        }
      }
    }

    private boolean dispatch(int methodLen, int pathStart, int pathEnd, int headerEnd, int contentLength)
        throws IOException {
      byte[] data = readBuffer.array();
      int offset = readBuffer.position();
      if (methodLen == 3 && data[offset] == 'G' && data[offset + 1] == 'E' && data[offset + 2] == 'T') {
        if (matches(data, offset + pathStart, pathEnd - pathStart, "/ready")) {
          writeAll(server.isReady() ? READY_RESPONSE : NOT_READY_RESPONSE);
          return true;
        }
        return false;
      }
      if (methodLen == 4 && data[offset] == 'P' && data[offset + 1] == 'O' && data[offset + 2] == 'S'
          && data[offset + 3] == 'T') {
        if (matches(data, offset + pathStart, pathEnd - pathStart, "/fraud-score")) {
          handleFraudScore(offset + headerEnd, contentLength);
          return true;
        }
        return false;
      }
      return false;
    }

    private void handleFraudScore(int bodyStart, int contentLength) throws IOException {
      byte[] data = readBuffer.array();
      byte[] parserBuffer = parser.buffer;
      int copyLen = Math.min(contentLength, parserBuffer.length);
      System.arraycopy(data, bodyStart, parserBuffer, 0, copyLen);
      int idx;
      try {
        parser.parseBuffer(copyLen);
        parser.parseRoot(request);
        idx = engine.evaluate(request, query, scratch);
        if (idx < 0)
          idx = 0;
        if (idx > 5)
          idx = 5;
      } catch (Exception ex) {
        // Fallback neutro — recusa a transação (FP em vez de FN), evitando
        // que uma falha de parsing pontue como fraude aprovada (peso 3).
        idx = 5;
      }
      writeAll(FRAUD_RESPONSES[idx]);
    }

    private void writeAll(byte[] payload) throws IOException {
      writeBuffer.clear();
      if (writeBuffer.capacity() < payload.length) {
        channel.write(ByteBuffer.wrap(payload));
        return;
      }
      writeBuffer.put(payload);
      writeBuffer.flip();
      while (writeBuffer.hasRemaining()) {
        int written = channel.write(writeBuffer);
        if (written <= 0) {
          Thread.onSpinWait();
        }
      }
    }

    private void sendAndClose(SelectionKey key, byte[] payload) throws IOException {
      writeAll(payload);
      closeQuietly(key);
    }

    private void closeQuietly(SelectionKey key) {
      try {
        channel.close();
      } catch (IOException ignored) {
      }
      key.cancel();
    }

    private static int findHeaderEnd(ByteBuffer buf) {
      int pos = buf.position();
      int lim = buf.limit();
      byte[] arr = buf.array();
      for (int i = pos; i + 3 < lim; i++) {
        if (arr[i] == '\r' && arr[i + 1] == '\n' && arr[i + 2] == '\r' && arr[i + 3] == '\n') {
          return (i + 4) - pos;
        }
      }
      return -1;
    }

    private static int scanMethod(ByteBuffer buf) {
      int pos = buf.position();
      int lim = buf.limit();
      byte[] arr = buf.array();
      for (int i = pos; i < lim; i++) {
        if (arr[i] == ' ') {
          return i - pos;
        }
      }
      return -1;
    }

    private static int scanPathEnd(ByteBuffer buf, int pathStart) {
      int pos = buf.position();
      int lim = buf.limit();
      byte[] arr = buf.array();
      for (int i = pos + pathStart; i < lim; i++) {
        if (arr[i] == ' ' || arr[i] == '\r' || arr[i] == '?') {
          return i - pos;
        }
      }
      return -1;
    }

    private static int parseContentLength(ByteBuffer buf, int headerEnd) {
      int pos = buf.position();
      int lim = pos + headerEnd;
      byte[] arr = buf.array();
      byte[] needle = { 'C', 'o', 'n', 't', 'e', 'n', 't', '-', 'L', 'e', 'n', 'g', 't', 'h', ':' };
      outer: for (int i = pos; i + needle.length < lim; i++) {
        for (int j = 0; j < needle.length; j++) {
          byte expected = needle[j];
          byte actual = arr[i + j];
          if (actual == expected)
            continue;
          if (expected >= 'A' && expected <= 'Z') {
            if ((actual ^ 0x20) == expected)
              continue;
          }
          continue outer;
        }
        int valueStart = i + needle.length;
        while (valueStart < lim && (arr[valueStart] == ' ' || arr[valueStart] == '\t')) {
          valueStart++;
        }
        int value = 0;
        while (valueStart < lim) {
          byte b = arr[valueStart];
          if (b < '0' || b > '9')
            break;
          value = value * 10 + (b - '0');
          valueStart++;
        }
        return value;
      }
      return 0;
    }

    private static boolean matches(byte[] data, int offset, int length, String expected) {
      if (length != expected.length())
        return false;
      for (int i = 0; i < length; i++) {
        if (data[offset + i] != expected.charAt(i))
          return false;
      }
      return true;
    }
  }

  private static byte[][] buildFraudResponses() {
    String[] bodies = {
        "{\"approved\":true,\"fraud_score\":0.0}",
        "{\"approved\":true,\"fraud_score\":0.2}",
        "{\"approved\":true,\"fraud_score\":0.4}",
        "{\"approved\":false,\"fraud_score\":0.6}",
        "{\"approved\":false,\"fraud_score\":0.8}",
        "{\"approved\":false,\"fraud_score\":1.0}"
    };
    byte[][] out = new byte[bodies.length][];
    for (int i = 0; i < bodies.length; i++) {
      String body = bodies[i];
      String full = "HTTP/1.1 200 OK\r\n"
          + "Content-Type: application/json\r\n"
          + "Content-Length: " + body.length() + "\r\n"
          + "Connection: keep-alive\r\n"
          + "\r\n"
          + body;
      out[i] = full.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    return out;
  }

  static HttpServerLoop forInet(int port, DetectionEngine engine, int workers) {
    return new HttpServerLoop(new InetSocketAddress(port), StandardProtocolFamily.INET, null, engine, workers);
  }

  static HttpServerLoop forUds(Path path, DetectionEngine engine, int workers) throws IOException {
    Files.deleteIfExists(path);
    Files.createDirectories(path.getParent());
    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(path);
    return new HttpServerLoop(address, StandardProtocolFamily.UNIX, path, engine, workers);
  }
}
