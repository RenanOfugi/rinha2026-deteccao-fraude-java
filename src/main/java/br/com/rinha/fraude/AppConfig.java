package br.com.rinha.fraude;

import java.nio.file.Path;

final class AppConfig {
    final int port;
    final Path resourcesDir;
    final Path indexDir;
    final int ivfClusters;
    final int ivfProbes;
    final int ivfRefineProbes;
    final int ivfSampleSize;
    final int kmeansIterations;
    final int ivfMaxBucketSize;
    final int httpWorkers;
    final boolean buildOnStartup;
    final Path udsPath;

    private AppConfig(
        int port,
        Path resourcesDir,
        Path indexDir,
        int ivfClusters,
        int ivfProbes,
        int ivfRefineProbes,
        int ivfSampleSize,
        int kmeansIterations,
        int ivfMaxBucketSize,
        int httpWorkers,
        boolean buildOnStartup,
        Path udsPath
    ) {
        this.port = port;
        this.resourcesDir = resourcesDir;
        this.indexDir = indexDir;
        this.ivfClusters = ivfClusters;
        this.ivfProbes = ivfProbes;
        this.ivfRefineProbes = ivfRefineProbes;
        this.ivfSampleSize = ivfSampleSize;
        this.kmeansIterations = kmeansIterations;
        this.ivfMaxBucketSize = ivfMaxBucketSize;
        this.httpWorkers = httpWorkers;
        this.buildOnStartup = buildOnStartup;
        this.udsPath = udsPath;
    }

    static AppConfig fromEnvironment() {
        String uds = stringEnv("RINHA_UDS_PATH", "");
        Path udsPath = uds.isBlank() ? null : Path.of(uds);
        return new AppConfig(
            intEnv("PORT", 8081),
            Path.of(stringEnv("RINHA_RESOURCES_DIR", "../rinha-de-backend-2026/resources")),
            Path.of(stringEnv("RINHA_INDEX_DIR", "./data/index")),
            intEnv("RINHA_IVF_CLUSTERS", 256),
            intEnv("RINHA_IVF_PROBES", 6),
            intEnv("RINHA_IVF_REFINE_PROBES", 10),
            intEnv("RINHA_IVF_SAMPLE_SIZE", 16_384),
            intEnv("RINHA_KMEANS_ITERATIONS", 6),
            intEnv("RINHA_IVF_MAX_BUCKET_SIZE", 20_000),
            intEnv("RINHA_HTTP_WORKERS", 1),
            boolEnv("RINHA_BUILD_ON_STARTUP", true),
            udsPath
        );
    }

    Path referencesFile() {
        return resourcesDir.resolve("references.json.gz");
    }

    Path normalizationFile() {
        return resourcesDir.resolve("normalization.json");
    }

    Path mccRiskFile() {
        return resourcesDir.resolve("mcc_risk.json");
    }

    Path metadataFile() {
        return indexDir.resolve("ivf.meta.bin");
    }

    Path vectorsFile() {
        return indexDir.resolve("vectors.bin");
    }

    Path labelsFile() {
        return indexDir.resolve("labels.bin");
    }

    private static String stringEnv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
}
