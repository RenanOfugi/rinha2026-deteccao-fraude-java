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
        ensureIndex(config);
        Vectorizer vectorizer = Vectorizer.load(config.normalizationFile(), config.mccRiskFile());
        IvfIndex index = IvfIndex.load(config);
        DetectionEngine engine = new DetectionEngine(vectorizer, index);

        HttpServerLoop loop = config.udsPath != null
            ? HttpServerLoop.forUds(config.udsPath, engine)
            : HttpServerLoop.forInet(config.port, engine);

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

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            loop.stop();
            try {
                loopThread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            index.close();
        }));
    }

    private static void ensureIndex(AppConfig config) throws IOException {
        if (Files.exists(config.metadataFile()) && Files.exists(config.vectorsFile()) && Files.exists(config.labelsFile())) {
            return;
        }
        if (!config.buildOnStartup) {
            throw new IOException("Indice IVF nao encontrado em " + config.indexDir);
        }
        IvfIndexBuilder.build(config);
    }
}
