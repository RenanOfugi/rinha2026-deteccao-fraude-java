package br.com.rinha.fraude;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Índice IVF com vetores quantizados em int16 (scale=10000).
 *
 * Layout em disco:
 * - vectors.bin: para cada vetor, STRIDE shorts (int16). Agrupados por bucket.
 * - labels.bin:  1 byte por vetor (0=legit, 1=fraud).
 * - meta.bin:    header + centroides int16 + bucketOffsets[clusterCount+1].
 *
 * Distâncias em long: diff máx = 20000, diff² = 400_000_000,
 * soma de 14 dims = 5_600_000_000 > Integer.MAX_VALUE.
 */
final class IvfIndex implements AutoCloseable {

    final int clusterCount;
    final int totalVectors;
    final int maxProbes;
    final double pruneMargin;
    final short[] centroids;
    final long[] bucketOffsets;
    final float[] bucketRadius; // raio (distância quantizada) de cada bucket
    private final Arena arena;
    private final MemorySegment vectorSegment;
    private final MemorySegment labelSegment;

    private IvfIndex(
            int clusterCount,
            int totalVectors,
            int maxProbes,
            double pruneMargin,
            short[] centroids,
            long[] bucketOffsets,
            float[] bucketRadius,
            Arena arena,
            MemorySegment vectorSegment,
            MemorySegment labelSegment) {
        this.clusterCount = clusterCount;
        this.totalVectors = totalVectors;
        this.maxProbes = maxProbes;
        this.pruneMargin = pruneMargin;
        this.centroids = centroids;
        this.bucketOffsets = bucketOffsets;
        this.bucketRadius = bucketRadius;
        this.arena = arena;
        this.vectorSegment = vectorSegment;
        this.labelSegment = labelSegment;
    }

    static IvfIndex load(AppConfig config) throws IOException {
        return load(config, config.metadataFile(), config.vectorsFile(), config.labelsFile());
    }

    /** Carrega uma partição específica (índice particionado por tag). */
    static IvfIndex loadPartition(AppConfig config, int tag) throws IOException {
        return load(config, config.metadataFile(tag), config.vectorsFile(tag), config.labelsFile(tag));
    }

    private static IvfIndex load(AppConfig config, java.nio.file.Path metaFile,
            java.nio.file.Path vecFile, java.nio.file.Path labFile) throws IOException {
        try (DataInputStream input = new DataInputStream(
                Files.newInputStream(metaFile))) {

            int magic = input.readInt();
            int version = input.readInt();
            if (magic != IvfIndexBuilder.MAGIC || version != IvfIndexBuilder.VERSION) {
                throw new IOException("Indice incompatível (magic="
                        + Integer.toHexString(magic) + ", version=" + version + ")");
            }
            int dimensions = input.readInt();
            int stride = input.readInt();
            int clusterCount = input.readInt();
            int totalVectors = input.readInt();
            int probes = input.readInt();
            if (dimensions != Vectorizer.DIMENSIONS || stride != Quantization.STRIDE) {
                throw new IOException("Dimensoes incompatíveis no indice");
            }

            // centroids: clusterCount × STRIDE shorts
            short[] centroids = new short[clusterCount * Quantization.STRIDE];
            for (int i = 0; i < centroids.length; i++) {
                centroids[i] = input.readShort();
            }

            long[] bucketOffsets = new long[clusterCount + 1];
            for (int i = 0; i < bucketOffsets.length; i++) {
                bucketOffsets[i] = input.readLong();
            }

            float[] bucketRadius = new float[clusterCount];
            for (int i = 0; i < clusterCount; i++) {
                bucketRadius[i] = input.readFloat();
            }

            Arena arena = Arena.ofShared();
            MemorySegment vectorSegment;
            MemorySegment labelSegment;
            try (FileChannel vc = FileChannel.open(vecFile, StandardOpenOption.READ);
                 FileChannel lc = FileChannel.open(labFile, StandardOpenOption.READ)) {
                vectorSegment = vc.map(FileChannel.MapMode.READ_ONLY, 0L, vc.size(), arena);
                labelSegment  = lc.map(FileChannel.MapMode.READ_ONLY, 0L, lc.size(), arena);
            }

            int effectiveMaxProbes = Math.max(1, Math.min(config.ivfMaxProbes, clusterCount));
            prefetch(vectorSegment);
            prefetch(labelSegment);

            return new IvfIndex(clusterCount, totalVectors, effectiveMaxProbes, config.ivfPruneMargin,
                    centroids, bucketOffsets, bucketRadius, arena, vectorSegment, labelSegment);
        }
    }

    int search(float[] queryFloat, SearchScratch scratch) {
        scratch.reset();
        short[] queryQuant = scratch.queryQuant;
        Quantization.quantize(queryFloat, queryQuant, 0);

        // Fase 1: para cada bucket, calcula o LOWER BOUND da distância de q a
        // qualquer vetor do bucket: lb = max(0, dist(q,c) - raio(c))². Esse limite
        // é exato (desigualdade triangular) — se lb > pior do top-K, o bucket
        // não pode conter vizinho melhor e é descartado SEM perder recall.
        final short[] cents = centroids;
        final int clusters = clusterCount;
        final long[] cLower = scratch.centroidDistances; // reusado p/ lower bounds
        final int[] cOrder = scratch.centroidOrder;
        final float[] radius = bucketRadius;
        for (int c = 0; c < clusters; c++) {
            long distC2 = QuantDistanceKernel.squared(cents, c * Quantization.STRIDE, queryQuant);
            double distC = Math.sqrt((double) distC2);
            double lb = distC - radius[c];
            cLower[c] = lb <= 0.0 ? 0L : (long) (lb * lb);
            cOrder[c] = c;
        }
        sortByDistance(cOrder, cLower, clusters);

        // Fase 2: visita buckets em ordem de lower bound crescente. Para quando
        // o lower bound do próximo bucket > pior do top-K (poda exata com
        // margin=1.0; margin<1 corta mais agressivo, margin>1 mais conservador).
        final short[] bucketBuffer = scratch.quantBucketBuffer;
        final byte[] labelBuffer = scratch.labelBuffer;
        final int chunkCap = SearchScratch.BUCKET_CHUNK;
        final double margin = pruneMargin;
        final int limit = Math.min(maxProbes, clusters);

        for (int i = 0; i < limit; i++) {
            int centroidId = cOrder[i];
            long lowerBound = cLower[centroidId];

            if (scratch.size >= SearchScratch.K) {
                long bound = (long) (scratch.worstDistance() * margin);
                if (lowerBound > bound) break;
            }

            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;

            long rOffset = 0L;
            long remaining = count;
            while (remaining > 0L) {
                int chunk = (int) Math.min(remaining, chunkCap);
                long byteOffset = (start + rOffset) * Quantization.STRIDE * Short.BYTES;
                MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT,
                        byteOffset, bucketBuffer, 0, chunk * Quantization.STRIDE);
                MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE,
                        start + rOffset, labelBuffer, 0, chunk);
                for (int v = 0; v < chunk; v++) {
                    long distance = QuantDistanceKernel.squared(
                            bucketBuffer, v * Quantization.STRIDE, queryQuant);
                    scratch.offerNeighbor(distance, labelBuffer[v]);
                }
                rOffset += chunk;
                remaining -= chunk;
            }
        }
        return scratch.scoreIndex();
    }

    /**
     * Ordena order[0..n) pela chave dist[order[i]] crescente. Quicksort com
     * fallback para insertion sort em subfaixas pequenas. O(n log n) — adequado
     * para clusterCount de 256 a 4096.
     */
    private static void sortByDistance(int[] order, long[] dist, int n) {
        quicksort(order, dist, 0, n - 1);
    }

    private static void quicksort(int[] order, long[] dist, int lo, int hi) {
        while (lo < hi) {
            if (hi - lo < 16) {
                for (int i = lo + 1; i <= hi; i++) {
                    int idx = order[i];
                    long d = dist[idx];
                    int j = i - 1;
                    while (j >= lo && dist[order[j]] > d) {
                        order[j + 1] = order[j];
                        j--;
                    }
                    order[j + 1] = idx;
                }
                return;
            }
            int mid = lo + (hi - lo) / 2;
            long pivot = dist[order[mid]];
            int i = lo, j = hi;
            while (i <= j) {
                while (dist[order[i]] < pivot) i++;
                while (dist[order[j]] > pivot) j--;
                if (i <= j) {
                    int t = order[i]; order[i] = order[j]; order[j] = t;
                    i++; j--;
                }
            }
            // Recursão na menor partição, loop na maior (limita pilha).
            if (j - lo < hi - i) {
                quicksort(order, dist, lo, j);
                lo = i;
            } else {
                quicksort(order, dist, i, hi);
                hi = j;
            }
        }
    }

    /**
     * Valida o IVF podado contra força bruta exata, para queries realistas.
     * Reporta: recall (% de queries com score idêntico ao exato), erro médio
     * de score, comparações médias por query, e quantos buckets visitados.
     */
    void selfTest() {
        // Mede recall em vários níveis de perturbação. Queries reais (k6) NÃO
        // estão no índice — caem entre clusters. Perturbação maior simula isso
        // e é um teste de recall mais honesto que perturbação mínima (que sempre
        // teria vizinho perfeito no próprio bucket).
        int[] perturbLevels = {200, 1000, 2000, 4000};
        for (int p : perturbLevels) {
            runSelfTest(p);
        }
    }

    private void runSelfTest(int perturbAmplitude) {
        java.util.SplittableRandom rng = new java.util.SplittableRandom(2026);
        SearchScratch scratch = new SearchScratch(clusterCount);
        short[] q = new short[Quantization.STRIDE];

        int queries = 500;
        int scoreMatches = 0;
        int scoreErrorSum = 0;
        long totalCompared = 0;
        long totalBuckets = 0;

        for (int it = 0; it < queries; it++) {
            int sampleVec = rng.nextInt(totalVectors);
            long byteOffset = (long) sampleVec * Quantization.STRIDE * Short.BYTES;
            MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset, q, 0, Quantization.STRIDE);
            for (int d = 0; d < Quantization.DIM; d++) {
                int nv = q[d] + (rng.nextInt(2 * perturbAmplitude + 1) - perturbAmplitude);
                if (nv < 0) nv = 0; else if (nv > 10000) nv = 10000;
                q[d] = (short) nv;
            }
            for (int d = Quantization.DIM; d < Quantization.STRIDE; d++) q[d] = 0;

            instrCompared = 0;
            instrBuckets = 0;
            int ivfScore = searchQuant(q, scratch);
            totalCompared += instrCompared;
            totalBuckets += instrBuckets;

            int bfScore = bruteForceScore(q);
            if (ivfScore == bfScore) scoreMatches++;
            scoreErrorSum += Math.abs(ivfScore - bfScore);
        }

        System.out.println("SELFTEST-IVF[perturb=" + perturbAmplitude + "]: queries=" + queries
                + " recall=" + String.format("%.2f%%", 100.0 * scoreMatches / queries)
                + " avgScoreErr=" + String.format("%.3f", (double) scoreErrorSum / queries)
                + " avgCompared=" + (totalCompared / queries)
                + " avgBuckets=" + (totalBuckets / queries)
                + " clusters=" + clusterCount + " maxProbes=" + maxProbes
                + " margin=" + pruneMargin);
    }

    private long instrCompared;
    private long instrBuckets;

    /** search() operando sobre query já quantizada, com instrumentação. */
    private int searchQuant(short[] queryQuant, SearchScratch scratch) {
        scratch.reset();
        final short[] cents = centroids;
        final int clusters = clusterCount;
        final long[] cLower = scratch.centroidDistances;
        final int[] cOrder = scratch.centroidOrder;
        final float[] radius = bucketRadius;
        for (int c = 0; c < clusters; c++) {
            long distC2 = QuantDistanceKernel.squared(cents, c * Quantization.STRIDE, queryQuant);
            double distC = Math.sqrt((double) distC2);
            double lb = distC - radius[c];
            cLower[c] = lb <= 0.0 ? 0L : (long) (lb * lb);
            cOrder[c] = c;
        }
        sortByDistance(cOrder, cLower, clusters);

        final short[] bucketBuffer = scratch.quantBucketBuffer;
        final byte[] labelBuffer = scratch.labelBuffer;
        final int chunkCap = SearchScratch.BUCKET_CHUNK;
        final double margin = pruneMargin;
        final int limit = Math.min(maxProbes, clusters);

        for (int i = 0; i < limit; i++) {
            int centroidId = cOrder[i];
            long lowerBound = cLower[centroidId];
            if (scratch.size >= SearchScratch.K) {
                long bound = (long) (scratch.worstDistance() * margin);
                if (lowerBound > bound) break;
            }
            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;
            instrBuckets++;
            long rOffset = 0L;
            long remaining = count;
            while (remaining > 0L) {
                int chunk = (int) Math.min(remaining, chunkCap);
                long byteOffset = (start + rOffset) * Quantization.STRIDE * Short.BYTES;
                MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT,
                        byteOffset, bucketBuffer, 0, chunk * Quantization.STRIDE);
                MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE,
                        start + rOffset, labelBuffer, 0, chunk);
                for (int v = 0; v < chunk; v++) {
                    long distance = QuantDistanceKernel.squared(
                            bucketBuffer, v * Quantization.STRIDE, queryQuant);
                    scratch.offerNeighbor(distance, labelBuffer[v]);
                }
                instrCompared += chunk;
                rOffset += chunk;
                remaining -= chunk;
            }
        }
        return scratch.scoreIndex();
    }

    /** Score exato via força bruta sobre todos os vetores. */
    private int bruteForceScore(short[] q) {
        long[] best = new long[SearchScratch.K];
        byte[] bestLab = new byte[SearchScratch.K];
        java.util.Arrays.fill(best, Long.MAX_VALUE);
        short[] buf = new short[Quantization.STRIDE];
        for (int v = 0; v < totalVectors; v++) {
            long byteOffset = (long) v * Quantization.STRIDE * Short.BYTES;
            MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset, buf, 0, Quantization.STRIDE);
            long dist = QuantDistanceKernel.squared(buf, 0, q);
            if (dist < best[SearchScratch.K - 1]) {
                byte lab = labelSegment.get(ValueLayout.JAVA_BYTE, v);
                int i = SearchScratch.K - 1;
                while (i > 0 && dist < best[i - 1]) {
                    best[i] = best[i - 1];
                    bestLab[i] = bestLab[i - 1];
                    i--;
                }
                best[i] = dist;
                bestLab[i] = lab;
            }
        }
        int frauds = 0;
        for (int i = 0; i < SearchScratch.K; i++) frauds += bestLab[i];
        return frauds;
    }

    @Override
    public void close() {
        arena.close();
    }

    private static void prefetch(MemorySegment segment) {
        long size = segment.byteSize();
        long acc = 0L;
        // Toca uma short por página (4096 bytes = 2048 shorts)
        for (long off = 0; off < size - 1; off += 4096L) {
            acc += segment.get(ValueLayout.JAVA_SHORT, off);
        }
        if (acc == Long.MIN_VALUE) System.out.print(""); // sink para evitar eliminação pelo JIT
    }
}
