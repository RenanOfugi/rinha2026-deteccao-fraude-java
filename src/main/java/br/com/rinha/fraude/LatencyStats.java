package br.com.rinha.fraude;

import java.util.concurrent.atomic.LongAdder;

/**
 * Coleta latências por estágio do pipeline em buckets logarítmicos sem
 * alocação no hot path. Imprime agregado periódico para identificar gargalos.
 *
 * Habilitado via env var RINHA_LATENCY_LOG=true. Quando desligado, todas as
 * operações são no-op e o JIT elimina via inlining + dead code elimination.
 *
 * Buckets (em nanosegundos):
 *   0: <1µs       (1000)
 *   1: <10µs      (10_000)
 *   2: <100µs     (100_000)
 *   3: <1ms       (1_000_000)
 *   4: <10ms      (10_000_000)
 *   5: <100ms     (100_000_000)
 *   6: >=100ms
 */
final class LatencyStats {
    static final boolean ENABLED = Boolean.parseBoolean(
        System.getenv().getOrDefault("RINHA_LATENCY_LOG", "false"));

    /**
     * Métricas só são contabilizadas após o servidor marcar ready=true. Antes
     * disso (warmup), os dados refletem JIT frio + alocações de inicialização
     * e não representam o regime estável que importa para o p99 final.
     */
    private static volatile boolean collecting = false;

    static void markCollecting() {
        collecting = true;
    }

    private static final int BUCKETS = 7;
    private static final long[] THRESHOLDS_NS = {
        1_000L,
        10_000L,
        100_000L,
        1_000_000L,
        10_000_000L,
        100_000_000L,
        Long.MAX_VALUE
    };
    private static final String[] BUCKET_LABELS = {
        "<1us", "<10us", "<100us", "<1ms", "<10ms", "<100ms", ">=100ms"
    };

    enum Stage {
        REQUEST_TOTAL,
        PARSE_JSON,
        VECTORIZE,
        SCAN_CENTROIDS,
        SCAN_BUCKETS_INITIAL,
        SCAN_BUCKETS_REFINE,
        WRITE_RESPONSE
    }

    /**
     * Buckets de distribuição do número de vetores varridos por chamada de
     * scanBuckets. Ajuda a distinguir cauda longa de causa externa (todos
     * buckets têm tamanho similar) vs desbalanceamento (alguns têm muito mais
     * vetores que outros).
     *
     * Buckets:
     *   0: <1k vetores
     *   1: <5k
     *   2: <10k
     *   3: <25k
     *   4: <50k
     *   5: <100k
     *   6: >=100k
     */
    private static final int VECTOR_COUNT_BUCKETS = 7;
    private static final int[] VECTOR_COUNT_THRESHOLDS = {1_000, 5_000, 10_000, 25_000, 50_000, 100_000, Integer.MAX_VALUE};
    private static final String[] VECTOR_COUNT_LABELS = {"<1k", "<5k", "<10k", "<25k", "<50k", "<100k", ">=100k"};
    private static final LongAdder[] VECTORS_SCANNED_COUNTS = initVectorBuckets();
    private static final LongAdder VECTORS_SCANNED_TOTAL = new LongAdder();
    private static final LongAdder VECTORS_SCANNED_CALLS = new LongAdder();
    private static final LongAdder VECTORS_SCANNED_MAX = new LongAdder();

    private static LongAdder[] initVectorBuckets() {
        LongAdder[] arr = new LongAdder[VECTOR_COUNT_BUCKETS];
        for (int i = 0; i < arr.length; i++) arr[i] = new LongAdder();
        return arr;
    }

    static void recordVectorsScanned(int count) {
        if (!ENABLED || !collecting) return;
        int bucket = 0;
        for (int i = 0; i < VECTOR_COUNT_BUCKETS - 1; i++) {
            if (count < VECTOR_COUNT_THRESHOLDS[i]) {
                bucket = i;
                break;
            }
            bucket = i + 1;
        }
        VECTORS_SCANNED_COUNTS[bucket].increment();
        VECTORS_SCANNED_TOTAL.add(count);
        VECTORS_SCANNED_CALLS.increment();
        long currentMax = VECTORS_SCANNED_MAX.sum();
        if (count > currentMax) {
            VECTORS_SCANNED_MAX.reset();
            VECTORS_SCANNED_MAX.add(count);
        }
    }

    // Um LongAdder por stage por bucket. LongAdder é low-contention (importante
    // se houver multi-thread no futuro; no single-thread atual o overhead é mínimo).
    private static final LongAdder[][] COUNTS = init();
    private static final LongAdder[] TOTAL_NS_PER_STAGE = initStageTotals();
    private static final LongAdder[] MAX_NS_PER_STAGE = initStageTotals();

    private static LongAdder[][] init() {
        LongAdder[][] arr = new LongAdder[Stage.values().length][BUCKETS];
        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < BUCKETS; j++) {
                arr[i][j] = new LongAdder();
            }
        }
        return arr;
    }

    private static LongAdder[] initStageTotals() {
        LongAdder[] arr = new LongAdder[Stage.values().length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new LongAdder();
        }
        return arr;
    }

    private LatencyStats() {}

    static long startIfEnabled() {
        return (ENABLED && collecting) ? System.nanoTime() : 0L;
    }

    static void record(Stage stage, long startNs) {
        if (!ENABLED || !collecting || startNs == 0L) return;
        long elapsed = System.nanoTime() - startNs;
        int bucket = bucketFor(elapsed);
        COUNTS[stage.ordinal()][bucket].increment();
        TOTAL_NS_PER_STAGE[stage.ordinal()].add(elapsed);
        // Atualização de max sem CAS (aceitável — é apenas diagnóstico, single-thread real).
        LongAdder maxAdder = MAX_NS_PER_STAGE[stage.ordinal()];
        long currentMax = maxAdder.sum();
        if (elapsed > currentMax) {
            maxAdder.reset();
            maxAdder.add(elapsed);
        }
    }

    private static int bucketFor(long ns) {
        for (int i = 0; i < BUCKETS - 1; i++) {
            if (ns < THRESHOLDS_NS[i]) return i;
        }
        return BUCKETS - 1;
    }

    static void startReporter() {
        if (!ENABLED) return;
        Thread t = Thread.ofPlatform()
            .name("latency-stats-reporter")
            .daemon(true)
            .unstarted(() -> {
                while (true) {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    dump();
                }
            });
        t.start();
    }

    private static void dump() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\n=== LatencyStats (5s window) ===\n");
        for (Stage stage : Stage.values()) {
            int ord = stage.ordinal();
            long total = 0L;
            for (int b = 0; b < BUCKETS; b++) {
                total += COUNTS[ord][b].sum();
            }
            if (total == 0L) continue;

            long totalNs = TOTAL_NS_PER_STAGE[ord].sum();
            long maxNs = MAX_NS_PER_STAGE[ord].sum();
            long avgNs = total > 0 ? totalNs / total : 0L;

            sb.append(String.format("%-22s n=%-7d avg=%6dus max=%7dus  ",
                stage.name(), total, avgNs / 1000L, maxNs / 1000L));

            for (int b = 0; b < BUCKETS; b++) {
                long c = COUNTS[ord][b].sum();
                if (c > 0) {
                    sb.append(BUCKET_LABELS[b]).append(":").append(c).append(" ");
                }
            }
            sb.append('\n');
        }
        long calls = VECTORS_SCANNED_CALLS.sum();
        if (calls > 0) {
            long totalVecs = VECTORS_SCANNED_TOTAL.sum();
            long maxVecs = VECTORS_SCANNED_MAX.sum();
            long avgVecs = totalVecs / calls;
            sb.append(String.format("%-22s n=%-7d avg=%6d max=%7d   ",
                "VECTORS_PER_SCAN", calls, avgVecs, maxVecs));
            for (int b = 0; b < VECTOR_COUNT_BUCKETS; b++) {
                long c = VECTORS_SCANNED_COUNTS[b].sum();
                if (c > 0) {
                    sb.append(VECTOR_COUNT_LABELS[b]).append(":").append(c).append(" ");
                }
            }
            sb.append('\n');
        }
        sb.append("================================\n");
        System.err.print(sb);
    }
}
