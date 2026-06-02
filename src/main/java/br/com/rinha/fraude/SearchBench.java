package br.com.rinha.fraude;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;

/**
 * Micro-benchmark do hot path de busca, por partição. Mede o wall-clock de
 * search() (Fase 1 lower-bounds+sort + Fase 2 scan) com queries realistas
 * (perturbação dos vetores reais). Reporta p50/p99/média em microssegundos.
 *
 * Uso: java ... bench   (com RINHA_INDEX_DIR apontando p/ índice particionado)
 */
final class SearchBench {

    private SearchBench() {
    }

    static void run(AppConfig config) throws Exception {
        int warmup = 50_000;
        int measure = 200_000;
        int perturb = 2000; // simula query real (não está no índice)

        for (int tag = 0; tag < AppConfig.N_PARTITIONS; tag++) {
            try (IvfIndex idx = IvfIndex.loadPartition(config, tag)) {
                benchPartition(tag, idx, warmup, measure, perturb);
            }
        }
    }

    private static void benchPartition(int tag, IvfIndex idx, int warmup, int measure, int perturb) {
        SplittableRandom rng = new SplittableRandom(2026 + tag);
        SearchScratch scratch = new SearchScratch(idx.clusterCount);

        // Pré-gera um pool de queries (floats) a partir de vetores reais perturbados.
        int pool = 4096;
        float[][] queries = new float[pool][Vectorizer.PADDED_DIMENSIONS];
        short[] qq = new short[Quantization.STRIDE];
        for (int p = 0; p < pool; p++) {
            int sampleVec = rng.nextInt(idx.totalVectors);
            long byteOffset = (long) sampleVec * Quantization.STRIDE * Short.BYTES;
            idx.copyVectorForBench(byteOffset, qq);
            for (int d = 0; d < Quantization.DIM; d++) {
                int nv = qq[d] + (rng.nextInt(2 * perturb + 1) - perturb);
                if (nv < 0) nv = 0; else if (nv > 10000) nv = 10000;
                queries[p][d] = nv / Quantization.SCALE;
            }
            for (int d = Quantization.DIM; d < Vectorizer.PADDED_DIMENSIONS; d++) queries[p][d] = 0f;
        }

        // Warmup
        int sink = 0;
        for (int i = 0; i < warmup; i++) {
            sink += idx.search(queries[i & (pool - 1)], scratch);
        }

        // Medição: nanos por query.
        long[] times = new long[measure];
        for (int i = 0; i < measure; i++) {
            float[] q = queries[i & (pool - 1)];
            long t0 = System.nanoTime();
            sink += idx.search(q, scratch);
            times[i] = System.nanoTime() - t0;
        }

        java.util.Arrays.sort(times);
        long sum = 0;
        for (long t : times) sum += t;
        double avgUs = sum / (double) measure / 1000.0;
        double p50 = times[measure / 2] / 1000.0;
        double p99 = times[(int) (measure * 0.99)] / 1000.0;
        double p999 = times[(int) (measure * 0.999)] / 1000.0;

        // Segunda passada instrumentada: breakdown por sub-fase (lower-bounds/sort/scan).
        long[] acc = new long[3];
        int n2 = 50_000;
        for (int i = 0; i < n2; i++) {
            sink += idx.searchTimed(queries[i & (pool - 1)], scratch, acc);
        }
        double lbUs = acc[0] / (double) n2 / 1000.0;
        double sortUs = acc[1] / (double) n2 / 1000.0;
        double scanUs = acc[2] / (double) n2 / 1000.0;

        System.out.printf(
            "BENCH tag=%d clusters=%d totalVec=%d  avg=%.2fus  p50=%.2fus  p99=%.2fus  p99.9=%.2fus%n"
          + "         breakdown: lowerBounds=%.2fus  sort=%.2fus  scan=%.2fus  (sink=%d)%n",
            tag, idx.clusterCount, idx.totalVectors, avgUs, p50, p99, p999,
            lbUs, sortUs, scanUs, sink & 0xff);
    }
}
