package br.com.rinha.fraude;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * kd-tree estática para KNN exato em 14-D. Substitui a busca IVF (que varria
 * ~50k vetores) por descida + backtracking podado (~centenas de comparações),
 * mantendo recall 100% (resultado idêntico à força bruta).
 *
 * Nós ficam na heap (arrays primitivos, cache-friendly). Vetores e labels
 * via mmap, reordenados pela construção (folhas contíguas → leitura sequencial).
 */
final class KdTree implements AutoCloseable {

    final int totalVectors;
    final int leafSize;

    // Nós (arrays paralelos). Folha: splitDim == -1.
    private final int[] splitDim;
    private final int[] splitValue;
    private final int[] left;
    private final int[] right;
    private final int[] leafStart;
    private final int[] leafCount;
    private final int rootIdx = 0;

    private final Arena arena;
    private final MemorySegment vectorSegment;
    private final MemorySegment labelSegment;

    private KdTree(int totalVectors, int leafSize,
            int[] splitDim, int[] splitValue, int[] left, int[] right,
            int[] leafStart, int[] leafCount,
            Arena arena, MemorySegment vectorSegment, MemorySegment labelSegment) {
        this.totalVectors = totalVectors;
        this.leafSize = leafSize;
        this.splitDim = splitDim;
        this.splitValue = splitValue;
        this.left = left;
        this.right = right;
        this.leafStart = leafStart;
        this.leafCount = leafCount;
        this.arena = arena;
        this.vectorSegment = vectorSegment;
        this.labelSegment = labelSegment;
    }

    static KdTree load(AppConfig config) throws IOException {
        byte[] raw = Files.readAllBytes(config.metadataFile());
        ByteBuffer meta = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        int magic = meta.getInt();
        int version = meta.getInt();
        if (magic != KdTreeBuilder.MAGIC || version != KdTreeBuilder.VERSION) {
            throw new IOException("Indice kd-tree incompativel (magic="
                    + Integer.toHexString(magic) + ", version=" + version + ")");
        }
        int dimensions = meta.getInt();
        int stride = meta.getInt();
        int totalVectors = meta.getInt();
        int nodeCount = meta.getInt();
        int leafSize = meta.getInt();
        if (dimensions != Vectorizer.DIMENSIONS || stride != Quantization.STRIDE) {
            throw new IOException("Dimensoes incompativeis no indice kd-tree");
        }

        int[] splitDim = new int[nodeCount];
        int[] splitValue = new int[nodeCount];
        int[] left = new int[nodeCount];
        int[] right = new int[nodeCount];
        int[] leafStart = new int[nodeCount];
        int[] leafCount = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            splitDim[i] = meta.getInt();
            splitValue[i] = meta.getInt();
            left[i] = meta.getInt();
            right[i] = meta.getInt();
            leafStart[i] = meta.getInt();
            leafCount[i] = meta.getInt();
        }

        Arena arena = Arena.ofShared();
        MemorySegment vectorSegment;
        MemorySegment labelSegment;
        try {
            try (FileChannel vc = FileChannel.open(config.vectorsFile(), StandardOpenOption.READ);
                 FileChannel lc = FileChannel.open(config.labelsFile(), StandardOpenOption.READ)) {
                vectorSegment = vc.map(FileChannel.MapMode.READ_ONLY, 0L, vc.size(), arena);
                labelSegment = lc.map(FileChannel.MapMode.READ_ONLY, 0L, lc.size(), arena);
            }
        } catch (Throwable t) {
            arena.close();
            throw t;
        }

        prefetch(vectorSegment);
        prefetchBytes(labelSegment);

        return new KdTree(totalVectors, leafSize, splitDim, splitValue, left, right,
                leafStart, leafCount, arena, vectorSegment, labelSegment);
    }

    /**
     * Busca KNN (K=SearchScratch.K) exata. Preenche scratch com os K vizinhos
     * mais próximos e retorna o score (número de fraudes entre eles, 0..K).
     */
    int search(float[] queryFloat, KdScratch scratch) {
        scratch.reset();
        short[] q = scratch.queryQuant;
        Quantization.quantize(queryFloat, q, 0);

        // Pilha iterativa de nós a visitar. Capacidade = profundidade máxima da
        // árvore (log2(3M/64) ≈ 16) com folga ampla.
        int[] stack = scratch.nodeStack;
        int sp = 0;
        stack[sp++] = rootIdx;

        final int[] sDim = splitDim;
        final int[] sVal = splitValue;
        final int[] lft = left;
        final int[] rgt = right;
        final int[] lStart = leafStart;
        final int[] lCount = leafCount;
        final short[] buf = scratch.leafBuffer;
        final byte[] labelBuf = scratch.leafLabels;

        while (sp > 0) {
            int node = stack[--sp];
            int dim = sDim[node];

            if (dim < 0) {
                // Folha: scan exaustivo do range.
                int start = lStart[node];
                int count = lCount[node];
                scanLeaf(start, count, q, buf, labelBuf, scratch);
                continue;
            }

            // Nó interno: desce primeiro pelo lado da query; empilha o outro
            // lado SOMENTE se ainda puder conter vizinho mais próximo que o
            // pior do top-K (poda por distância ao plano de split).
            int qv = q[dim];
            int split = sVal[node];
            long diff = (long) qv - split;
            long planeDist2 = diff * diff;

            int near, far;
            if (qv < split) { near = lft[node]; far = rgt[node]; }
            else { near = rgt[node]; far = lft[node]; }

            // Empilha far primeiro (visitado depois), near por último (visitado já).
            // Só empilha far se a esfera do top-K cruza o plano.
            if (planeDist2 < scratch.worstDistance()) {
                stack[sp++] = far;
            }
            stack[sp++] = near;
        }

        return scratch.scoreIndex();
    }

    private void scanLeaf(int start, int count, short[] q, short[] buf, byte[] labelBuf,
            KdScratch scratch) {
        long byteOffset = (long) start * Quantization.STRIDE * Short.BYTES;
        MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset,
                buf, 0, count * Quantization.STRIDE);
        MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE, start, labelBuf, 0, count);
        for (int v = 0; v < count; v++) {
            long dist = QuantDistanceKernel.squared(buf, v * Quantization.STRIDE, q);
            scratch.offerNeighbor(dist, labelBuf[v]);
        }
    }

    /**
     * Valida que a kd-tree retorna o mesmo conjunto de K distâncias que a força
     * bruta exaustiva, para queries aleatórias. KNN exato ⇒ 0 divergências.
     * Também reporta comparações médias por query (deve ser ~centenas, não ~50k).
     */
    void selfTest() {
        java.util.SplittableRandom rng = new java.util.SplittableRandom(12345);
        KdScratch scratch = new KdScratch(leafSize);
        short[] q = new short[Quantization.STRIDE];

        int queries = 300;
        int mismatches = 0;
        long totalCompared = 0;

        for (int it = 0; it < queries; it++) {
            // Usa vetores REAIS do dataset como query (transações reais caem
            // perto de clusters densos — representam a carga de produção). Pega
            // um vetor aleatório do índice e perturba levemente, para não ser
            // distância zero exata. Queries uniformes aleatórias seriam
            // pessimistas (espaço esparso ⇒ esfera KNN gigante ⇒ poda falha).
            int sampleVec = rng.nextInt(totalVectors);
            long byteOffset = (long) sampleVec * Quantization.STRIDE * Short.BYTES;
            MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset, q, 0, Quantization.STRIDE);
            // Perturbação pequena em algumas dimensões.
            for (int d = 0; d < Quantization.DIM; d++) {
                if (rng.nextInt(3) == 0) {
                    int nv = q[d] + (rng.nextInt(401) - 200);
                    if (nv < 0) nv = 0; else if (nv > 10000) nv = 10000;
                    q[d] = (short) nv;
                }
            }
            for (int d = Quantization.DIM; d < Quantization.STRIDE; d++) q[d] = 0;

            // kd-tree
            long[] kdDist = searchRaw(q, scratch);

            // força bruta sobre todos os vetores
            long[] bfDist = bruteForce(q);

            // Compara os K conjuntos de distâncias (ordenados).
            for (int k = 0; k < KdScratch.K; k++) {
                if (kdDist[k] != bfDist[k]) {
                    if (mismatches < 10) {
                        System.err.println("SELFTEST-KD mismatch query=" + it + " k=" + k
                                + " kd=" + kdDist[k] + " bf=" + bfDist[k]);
                    }
                    mismatches++;
                    break;
                }
            }
            totalCompared += lastCompared;
        }
        System.out.println("SELFTEST-KD: queries=" + queries + " mismatches=" + mismatches
                + " avgCompared=" + (totalCompared / queries)
                + " (vs forca bruta=" + totalVectors + ")");
    }

    private long lastCompared;

    /** Executa a busca e devolve as K distâncias ordenadas (para o self-test). */
    private long[] searchRaw(short[] q, KdScratch scratch) {
        scratch.reset();
        lastCompared = 0;
        int[] stack = scratch.nodeStack;
        int sp = 0;
        stack[sp++] = rootIdx;
        final short[] buf = scratch.leafBuffer;
        final byte[] labelBuf = scratch.leafLabels;
        while (sp > 0) {
            int node = stack[--sp];
            int dim = splitDim[node];
            if (dim < 0) {
                int start = leafStart[node];
                int count = leafCount[node];
                long byteOffset = (long) start * Quantization.STRIDE * Short.BYTES;
                MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset,
                        buf, 0, count * Quantization.STRIDE);
                MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE, start, labelBuf, 0, count);
                for (int v = 0; v < count; v++) {
                    long dist = QuantDistanceKernel.squared(buf, v * Quantization.STRIDE, q);
                    scratch.offerNeighbor(dist, labelBuf[v]);
                    lastCompared++;
                }
                continue;
            }
            int qv = q[dim];
            int split = splitValue[node];
            long diff = (long) qv - split;
            long planeDist2 = diff * diff;
            int near, far;
            if (qv < split) { near = left[node]; far = right[node]; }
            else { near = right[node]; far = left[node]; }
            if (planeDist2 < scratch.worstDistance()) stack[sp++] = far;
            stack[sp++] = near;
        }
        return scratch.bestDistances.clone();
    }

    /** Top-K distâncias por força bruta sobre todos os vetores (verdade absoluta). */
    private long[] bruteForce(short[] q) {
        long[] best = new long[KdScratch.K];
        java.util.Arrays.fill(best, Long.MAX_VALUE);
        short[] buf = new short[Quantization.STRIDE];
        for (int v = 0; v < totalVectors; v++) {
            long byteOffset = (long) v * Quantization.STRIDE * Short.BYTES;
            MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT, byteOffset,
                    buf, 0, Quantization.STRIDE);
            long dist = QuantDistanceKernel.squared(buf, 0, q);
            // insere no top-K
            if (dist < best[KdScratch.K - 1]) {
                int i = KdScratch.K - 1;
                while (i > 0 && dist < best[i - 1]) {
                    best[i] = best[i - 1];
                    i--;
                }
                best[i] = dist;
            }
        }
        return best;
    }

    @Override
    public void close() {
        arena.close();
    }

    private static void prefetch(MemorySegment segment) {
        long size = segment.byteSize();
        long acc = 0L;
        for (long off = 0; off < size - 1; off += 4096L) {
            acc += segment.get(ValueLayout.JAVA_SHORT, off);
        }
        if (acc == Long.MIN_VALUE) System.out.print("");
    }

    private static void prefetchBytes(MemorySegment segment) {
        long size = segment.byteSize();
        long acc = 0L;
        for (long off = 0; off < size; off += 4096L) {
            acc += segment.get(ValueLayout.JAVA_BYTE, off);
        }
        if (acc == Long.MIN_VALUE) System.out.print("");
    }
}
