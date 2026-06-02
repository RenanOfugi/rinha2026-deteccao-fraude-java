package br.com.rinha.fraude;

import java.io.IOException;
import java.nio.file.Files;

public final class FraudDetectionApplication {

    private static final int WARMUP_ITERATIONS = Integer.getInteger("rinha.warmup.iterations", 4000);

    private FraudDetectionApplication() {
    }

    public static void main(String[] args) throws Throwable {
        // Carrega FdReceiver ANTES de qualquer inicialização de FFM/MemorySegment.
        // A inicialização da Foreign Memory API (Arena/MemorySegment no IvfIndex)
        // sela o acesso reflexivo a sun.nio.ch; carregar o <clinit> do FdReceiver
        // (que faz privateLookupIn em SocketChannelImpl) primeiro evita o
        // IllegalAccessException. Só no modo FD passing.
        if (System.getenv("RINHA_FD_SOCKET") != null && !System.getenv("RINHA_FD_SOCKET").isBlank()) {
            Class.forName("br.com.rinha.fraude.FdReceiver");
        }
        AppConfig config = AppConfig.fromEnvironment();
        if (args.length > 0 && "build-index".equals(args[0])) {
            IvfIndexBuilder.build(config);
            return;
        }
        if (args.length > 0 && "self-test-fd".equals(args[0])) {
            FdSelfTest.run();
            return;
        }
        if (args.length > 0 && "self-test-ivf".equals(args[0])) {
            try (IvfIndex idx = IvfIndex.load(config)) {
                idx.selfTest();
            }
            return;
        }
        if (args.length > 0 && "self-test-partitioned".equals(args[0])) {
            for (int tag = 0; tag < AppConfig.N_PARTITIONS; tag++) {
                System.out.println("--- Particao tag=" + tag + " ---");
                try (IvfIndex idx = IvfIndex.loadPartition(config, tag)) {
                    idx.selfTest();
                }
            }
            return;
        }
        ensureIndex(config);
        Vectorizer vectorizer = Vectorizer.load(config.normalizationFile(), config.mccRiskFile());
        DetectionEngine engine = DetectionEngine.load(config, vectorizer);

        // Modo FD passing: se RINHA_FD_SOCKET estiver definido, o LB (Rust) aceita
        // o TCP e passa o fd; a API só sobe os workers + um FdReceiver. Caso
        // contrário, usa o accept loop tradicional (nginx via UDS/TCP).
        String fdSocket = System.getenv("RINHA_FD_SOCKET");
        HttpServerLoop loop;
        Thread loopThread = null;
        FdReceiver fdReceiver = null;
        if (fdSocket != null && !fdSocket.isBlank()) {
            loop = HttpServerLoop.forFdPassing(engine, config.httpWorkers);
            loop.startWorkers();
            fdReceiver = new FdReceiver(fdSocket, loop::injectChannel);
            fdReceiver.start();
            System.out.println("Modo FD passing: control socket " + fdSocket
                + ", " + config.httpWorkers + " worker(s)");
        } else {
            loop = config.udsPath != null
                ? HttpServerLoop.forUds(config.udsPath, engine, config.httpWorkers)
                : HttpServerLoop.forInet(config.port, engine, config.httpWorkers);
            loopThread = Thread.ofPlatform()
                .name("rinha-http-loop")
                .daemon(false)
                .start(loop);
        }
        final Thread loopThreadF = loopThread;
        final FdReceiver fdReceiverF = fdReceiver;

        // /ready começa retornando 503. Esquentamos JIT e cache de páginas
        // ANTES de marcar pronto, para que o k6 só comece a fazer carga quando
        // a aplicação estiver de fato no regime estável.
        long t0 = System.nanoTime();
        try {
            Warmup.run(engine, WARMUP_ITERATIONS);
        } catch (Exception ex) {
            System.err.println("Warmup falhou: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000L;
        loop.markReady();
        System.out.println("Warmup concluido em " + durationMs + " ms (" + WARMUP_ITERATIONS + " iteracoes) — pronto para receber carga");

        // Modo AOT_TRAINING: usado no build com -XX:AOTMode=record para o JIT
        // gravar o profile compilado do hot path. Após o warmup (que já exerceu
        // engine.evaluate milhares de vezes), o profile está pronto e a JVM sai
        // limpa para o AOTMode=create gerar o cache.
        if ("1".equals(System.getenv("AOT_TRAINING"))) {
            System.out.println("AOT_TRAINING=1: saindo apos warmup para gravar profile AOT");
            loop.stop();
            engine.close();
            System.exit(0);
        }

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            loop.stop();
            if (fdReceiverF != null) fdReceiverF.stop();
            if (loopThreadF != null) {
                try {
                    loopThreadF.join(2000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            engine.close();
        }));

        // No modo FD passing, a main não tem o loop bloqueante — segura a JVM viva.
        if (fdReceiverF != null) {
            Thread.currentThread().join();
        }
    }

    private static void ensureIndex(AppConfig config) throws IOException {
        boolean allPresent = true;
        for (int tag = 0; tag < AppConfig.N_PARTITIONS; tag++) {
            if (!Files.exists(config.metadataFile(tag))
                    || !Files.exists(config.vectorsFile(tag))
                    || !Files.exists(config.labelsFile(tag))) {
                allPresent = false;
                break;
            }
        }
        if (allPresent) {
            return;
        }
        if (!config.buildOnStartup) {
            throw new IOException("Indice IVF particionado nao encontrado em " + config.indexDir);
        }
        IvfIndexBuilder.build(config);
    }
}
