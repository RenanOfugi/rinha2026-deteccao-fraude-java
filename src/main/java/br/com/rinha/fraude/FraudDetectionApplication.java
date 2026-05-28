package br.com.rinha.fraude;

import java.io.IOException;
import java.nio.file.Files;

public final class FraudDetectionApplication {

    private static final int WARMUP_ITERATIONS = Integer.getInteger("rinha.warmup.iterations", 4000);

    private FraudDetectionApplication() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment();
        if (args.length > 0 && "build-index".equals(args[0])) {
            IvfIndexBuilder.build(config);
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

        HttpServerLoop loop = config.udsPath != null
            ? HttpServerLoop.forUds(config.udsPath, engine, config.httpWorkers)
            : HttpServerLoop.forInet(config.port, engine, config.httpWorkers);

        Thread loopThread = Thread.ofPlatform()
            .name("rinha-http-loop")
            .daemon(false)
            .start(loop);

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
            try {
                loopThread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            engine.close();
        }));
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
