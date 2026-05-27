package br.com.rinha.fraude;

import java.util.Arrays;

/**
 * Estado reutilizável por conexão para a busca kd-tree. Confinado a uma thread.
 * Top-K por inserção ordenada (K pequeno), pilha de nós para a descida iterativa,
 * e buffer para o scan SIMD da folha.
 */
final class KdScratch {
    static final int K = 5;

    final long[] bestDistances = new long[K];
    final byte[] bestLabels = new byte[K];
    final short[] queryQuant = new short[Quantization.STRIDE];

    // Pilha de nós: profundidade da árvore é ~log2(N/leafSize). Para 3M/64 ≈ 16
    // níveis; cada nível pode empilhar até 2 nós no backtracking. 256 é folga
    // ampla e seguro contra árvores desbalanceadas.
    final int[] nodeStack = new int[256];

    // Buffer da folha: leafSize vetores. Dimensionado no construtor.
    final short[] leafBuffer;
    final byte[] leafLabels;

    int size;

    KdScratch(int leafSize) {
        this.leafBuffer = new short[leafSize * Quantization.STRIDE];
        this.leafLabels = new byte[leafSize];
        Arrays.fill(bestDistances, Long.MAX_VALUE);
    }

    void reset() {
        Arrays.fill(bestDistances, Long.MAX_VALUE);
        Arrays.fill(bestLabels, (byte) 0);
        size = 0;
    }

    /** Pior distância do top-K atual (Long.MAX_VALUE enquanto não cheio). */
    long worstDistance() {
        // Enquanto não temos K vizinhos, qualquer região pode conter candidato:
        // retorna MAX para nunca podar.
        if (size < K) return Long.MAX_VALUE;
        return bestDistances[K - 1];
    }

    void offerNeighbor(long distance, byte label) {
        final long[] best = bestDistances;
        final byte[] labs = bestLabels;
        int s = size;
        if (s == K) {
            if (distance >= best[K - 1]) return;
            int i = K - 1;
            while (i > 0 && distance < best[i - 1]) {
                best[i] = best[i - 1];
                labs[i] = labs[i - 1];
                i--;
            }
            best[i] = distance;
            labs[i] = label;
            return;
        }
        int insertAt = s;
        size = s + 1;
        while (insertAt > 0 && distance < best[insertAt - 1]) {
            best[insertAt] = best[insertAt - 1];
            labs[insertAt] = labs[insertAt - 1];
            insertAt--;
        }
        best[insertAt] = distance;
        labs[insertAt] = label;
    }

    int scoreIndex() {
        int s = size;
        int frauds = 0;
        for (int i = 0; i < s; i++) frauds += bestLabels[i];
        if (s == K) return frauds;
        if (s <= 0) return 0;
        float ratio = (float) frauds / (float) s;
        int idx = Math.round(ratio * K);
        if (idx < 0) return 0;
        if (idx > K) return K;
        return idx;
    }
}
