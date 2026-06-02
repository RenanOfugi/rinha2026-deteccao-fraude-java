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
    final short[] bboxMin; // [cluster*DIM + dim] menor valor da dim no bucket
    final short[] bboxMax; // [cluster*DIM + dim] maior valor da dim no bucket
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
            short[] bboxMin,
            short[] bboxMax,
            Arena arena,
            MemorySegment vectorSegment,
            MemorySegment labelSegment) {
        this.clusterCount = clusterCount;
        this.totalVectors = totalVectors;
        this.maxProbes = maxProbes;
        this.pruneMargin = pruneMargin;
        this.centroids = centroids;
        this.bucketOffsets = bucketOffsets;
        this.bboxMin = bboxMin;
        this.bboxMax = bboxMax;
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

            // Disco grava bbox com stride DIM=14 (sem padding). Em memória,
            // re-layout para STRIDE=16 (2 shorts de padding zerados por cluster):
            // isso alinha as leituras SIMD de 8+8 shorts ao limite do cluster, e
            // o padding (min=max=0) dá gap 0 — não contamina o lower bound.
            short[] bboxMin = new short[clusterCount * Quantization.STRIDE];
            short[] bboxMax = new short[clusterCount * Quantization.STRIDE];
            for (int c = 0; c < clusterCount; c++) {
                int dst = c * Quantization.STRIDE;
                for (int d = 0; d < Quantization.DIM; d++) {
                    bboxMin[dst + d] = input.readShort();
                }
            }
            for (int c = 0; c < clusterCount; c++) {
                int dst = c * Quantization.STRIDE;
                for (int d = 0; d < Quantization.DIM; d++) {
                    bboxMax[dst + d] = input.readShort();
                }
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
                    centroids, bucketOffsets, bboxMin, bboxMax, arena, vectorSegment, labelSegment);
        }
    }

    /** Copia um vetor cru do segmento mmap (uso de benchmark). */
    void copyVectorForBench(long byteOffset, short[] dst) {
        MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset, dst, 0, Quantization.STRIDE);
    }

    /**
     * search() instrumentado por sub-fase (uso de benchmark). Preenche acc[]:
     * acc[0] += nanos em lower-bounds, acc[1] += nanos em sort, acc[2] += nanos
     * em scan. Mesma lógica de search() — só com cronômetros entre as fases.
     */
    int searchTimed(float[] queryFloat, SearchScratch scratch, long[] acc) {
        scratch.reset();
        short[] queryQuant = scratch.queryQuant;
        Quantization.quantize(queryFloat, queryQuant, 0);

        final int clusters = clusterCount;
        final long[] hk = scratch.heapKeys;
        final int[] hv = scratch.heapVals;

        long t0 = System.nanoTime();
        for (int c = 0; c < clusters; c++) {
            hk[c] = bboxLowerBound(c, queryQuant);
            hv[c] = c;
        }
        long t1 = System.nanoTime();
        scratch.heapSize = clusters;
        heapify(hk, hv, clusters);
        long t2 = System.nanoTime();

        final double margin = pruneMargin;
        final int limit = Math.min(maxProbes, clusters);
        for (int i = 0; i < limit && scratch.heapSize > 0; i++) {
            long lowerBound = hk[0];
            if (scratch.size >= SearchScratch.K) {
                long bound = (long) (scratch.worstDistance() * margin);
                if (lowerBound > bound) break;
            }
            int centroidId = extractMin(hk, hv, scratch);
            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;
            long base = start * Quantization.STRIDE * Short.BYTES;
            for (long v = 0; v < count; v++) {
                long byteOffset = base + v * Quantization.STRIDE * Short.BYTES;
                long distance = QuantDistanceKernel.squaredFromSegment(
                        vectorSegment, byteOffset, queryQuant);
                if (distance < scratch.worstDistance() || scratch.size < SearchScratch.K) {
                    byte label = labelSegment.get(ValueLayout.JAVA_BYTE, start + v);
                    scratch.offerNeighbor(distance, label);
                }
            }
        }
        long t3 = System.nanoTime();
        acc[0] += t1 - t0;
        acc[1] += t2 - t1;
        acc[2] += t3 - t2;
        return scratch.scoreIndex();
    }

    int search(float[] queryFloat, SearchScratch scratch) {
        scratch.reset();
        short[] queryQuant = scratch.queryQuant;
        Quantization.quantize(queryFloat, queryQuant, 0);

        // Fase 1: lower bound = distância² da query à BOUNDING BOX de cada bucket:
        // Σ_dim max(0, min[d]-q[d], q[d]-max[d])². Limite inferior exato — se lb >
        // pior do top-K, o bucket não pode conter vizinho melhor (poda sem perder
        // recall). Em vez de ordenar TODOS os 2048 lower-bounds (O(n log n)), só
        // precisamos visitá-los em ordem crescente ATÉ a poda parar (~10-60 de
        // 2048). Construímos um min-heap em O(n) e extraímos sob demanda — o sort
        // total era ~55% do tempo de busca; o heap troca isso por O(n + k log n).
        final int clusters = clusterCount;
        final long[] hk = scratch.heapKeys;
        final int[] hv = scratch.heapVals;
        for (int c = 0; c < clusters; c++) {
            hk[c] = bboxLowerBound(c, queryQuant);
            hv[c] = c;
        }
        scratch.heapSize = clusters;
        heapify(hk, hv, clusters);

        // Fase 2: extrai buckets do heap em ordem de lower bound crescente. Para
        // quando o menor lower bound restante > pior do top-K (margin=1.0; <1 corta
        // mais agressivo, >1 mais conservador).
        final double margin = pruneMargin;
        final int limit = Math.min(maxProbes, clusters);

        for (int i = 0; i < limit && scratch.heapSize > 0; i++) {
            long lowerBound = hk[0];
            if (scratch.size >= SearchScratch.K) {
                long bound = (long) (scratch.worstDistance() * margin);
                if (lowerBound > bound) break;
            }
            int centroidId = extractMin(hk, hv, scratch);

            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;

            // Scan lendo o vetor DIRETO do mmap (sem cópia para bucketBuffer).
            // A cópia de até 24k×16 shorts era pura perda de banda; o kernel
            // SIMD lê o MemorySegment in-place. Labels são 1 byte — leitura direta.
            long base = start * Quantization.STRIDE * Short.BYTES;
            for (long v = 0; v < count; v++) {
                long byteOffset = base + v * Quantization.STRIDE * Short.BYTES;
                long distance = QuantDistanceKernel.squaredFromSegment(
                        vectorSegment, byteOffset, queryQuant);
                if (distance < scratch.worstDistance() || scratch.size < SearchScratch.K) {
                    byte label = labelSegment.get(ValueLayout.JAVA_BYTE, start + v);
                    scratch.offerNeighbor(distance, label);
                }
            }
        }
        return scratch.scoreIndex();
    }

    /** Heapify bottom-up: O(n). Min-heap por key, val carrega o clusterId junto. */
    private static void heapify(long[] key, int[] val, int n) {
        for (int i = (n >> 1) - 1; i >= 0; i--) {
            siftDown(key, val, i, n);
        }
    }

    /** Extrai o mínimo do min-heap (raiz). Decrementa scratch.heapSize. */
    private static int extractMin(long[] key, int[] val, SearchScratch scratch) {
        int n = scratch.heapSize;
        int min = val[0];
        int last = n - 1;
        key[0] = key[last];
        val[0] = val[last];
        scratch.heapSize = last;
        siftDown(key, val, 0, last);
        return min;
    }

    private static void siftDown(long[] key, int[] val, int i, int n) {
        long k = key[i];
        int v = val[i];
        while (true) {
            int left = 2 * i + 1;
            if (left >= n) break;
            int smallest = left;
            int right = left + 1;
            if (right < n && key[right] < key[left]) smallest = right;
            if (key[smallest] >= k) break;
            key[i] = key[smallest];
            val[i] = val[smallest];
            i = smallest;
        }
        key[i] = k;
        val[i] = v;
    }

    /**
     * Lower bound (distância²) da query à bounding box do bucket: para cada
     * dimensão, a folga é 0 se q[d] está dentro de [min,max], senão é a distância
     * até a borda mais próxima. Soma dos quadrados das folgas. Limite inferior
     * exato da distância de q a qualquer vetor do bucket. bboxMin/bboxMax têm
     * stride STRIDE=16 (padding zerado nas dims 14-15 → gap 0). Delega ao kernel
     * SIMD se disponível; senão usa o fallback escalar.
     */
    private long bboxLowerBound(int cluster, short[] q) {
        final int base = cluster * Quantization.STRIDE;
        if (BBOX_SIMD) {
            return SimdBboxKernel.lowerBound(bboxMin, bboxMax, base, q);
        }
        final short[] mn = bboxMin;
        final short[] mx = bboxMax;
        long lb = 0L;
        for (int d = 0; d < Quantization.DIM; d++) {
            int qd = q[d];
            int lo = mn[base + d];
            int hi = mx[base + d];
            int gap;
            if (qd < lo) gap = lo - qd;
            else if (qd > hi) gap = qd - hi;
            else continue; // dentro da caixa nesta dim: folga 0
            lb += (long) gap * gap;
        }
        return lb;
    }

    /** Resolve uma única vez se o kernel SIMD do bbox está disponível. */
    private static final boolean BBOX_SIMD = resolveBboxSimd();

    private static boolean resolveBboxSimd() {
        try {
            Class.forName("br.com.rinha.fraude.SimdBboxKernel");
            // Sanidade: bate o resultado SIMD com o escalar num caso conhecido.
            short[] mn = new short[Quantization.STRIDE];
            short[] mx = new short[Quantization.STRIDE];
            short[] q  = new short[Quantization.STRIDE];
            // Caixa [100,200] nas 14 dims úteis; q DENTRO em todas exceto duas.
            for (int d = 0; d < Quantization.DIM; d++) { mn[d] = 100; mx[d] = 200; q[d] = 150; }
            q[0] = 50;   // abaixo: gap 50
            q[1] = 250;  // acima:  gap 50
            // demais dims: dentro (gap 0); padding (14,15): lo=hi=q=0 (gap 0).
            long simd = SimdBboxKernel.lowerBound(mn, mx, 0, q);
            long expected = 50L * 50 + 50L * 50;
            return simd == expected;
        } catch (Throwable t) {
            return false;
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

    /** search() operando sobre query já quantizada, com instrumentação. Usa o
     *  mesmo caminho (heap lazy) do search() de produção — valida o recall do
     *  código real, não de um caminho paralelo. */
    private int searchQuant(short[] queryQuant, SearchScratch scratch) {
        scratch.reset();
        final int clusters = clusterCount;
        final long[] hk = scratch.heapKeys;
        final int[] hv = scratch.heapVals;
        for (int c = 0; c < clusters; c++) {
            hk[c] = bboxLowerBound(c, queryQuant);
            hv[c] = c;
        }
        scratch.heapSize = clusters;
        heapify(hk, hv, clusters);

        final double margin = pruneMargin;
        final int limit = Math.min(maxProbes, clusters);

        for (int i = 0; i < limit && scratch.heapSize > 0; i++) {
            long lowerBound = hk[0];
            if (scratch.size >= SearchScratch.K) {
                long bound = (long) (scratch.worstDistance() * margin);
                if (lowerBound > bound) break;
            }
            int centroidId = extractMin(hk, hv, scratch);
            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;
            instrBuckets++;
            long base = start * Quantization.STRIDE * Short.BYTES;
            for (long v = 0; v < count; v++) {
                long byteOffset = base + v * Quantization.STRIDE * Short.BYTES;
                long distance = QuantDistanceKernel.squaredFromSegment(
                        vectorSegment, byteOffset, queryQuant);
                if (distance < scratch.worstDistance() || scratch.size < SearchScratch.K) {
                    byte label = labelSegment.get(ValueLayout.JAVA_BYTE, start + v);
                    scratch.offerNeighbor(distance, label);
                }
                instrCompared++;
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
