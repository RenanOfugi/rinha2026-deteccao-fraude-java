package br.com.rinha.fraude;

import java.util.Arrays;

final class SearchScratch {
    static final int K = 5;
    static final int BUCKET_CHUNK = 1024;

    final long[] bestDistances = new long[K];
    final byte[] bestLabels = new byte[K];
    // Todos os centroides, para ordená-los por distância e visitar buckets em
    // ordem de proximidade com poda dinâmica.
    final long[] centroidDistances;
    final int[] centroidOrder;
    final short[] quantBucketBuffer = new short[BUCKET_CHUNK * Quantization.STRIDE];
    final byte[] labelBuffer = new byte[BUCKET_CHUNK];
    final short[] queryQuant = new short[Quantization.STRIDE];
    int size;

    SearchScratch(int clusterCount) {
        this.centroidDistances = new long[clusterCount];
        this.centroidOrder = new int[clusterCount];
        Arrays.fill(bestDistances, Long.MAX_VALUE);
    }

    void reset() {
        Arrays.fill(bestDistances, Long.MAX_VALUE);
        Arrays.fill(bestLabels, (byte) 0);
        size = 0;
    }

    void offerNeighbor(long distance, byte label) {
        int currentSize = size;
        if (currentSize == K && distance >= bestDistances[K - 1]) {
            return;
        }
        int insertAt = currentSize < K ? currentSize : K - 1;
        if (currentSize < K) {
            size = currentSize + 1;
        }
        while (insertAt > 0 && distance < bestDistances[insertAt - 1]) {
            bestDistances[insertAt] = bestDistances[insertAt - 1];
            bestLabels[insertAt] = bestLabels[insertAt - 1];
            insertAt--;
        }
        bestDistances[insertAt] = distance;
        bestLabels[insertAt] = label;
    }

    /** Pior distância do top-K atual (MAX enquanto não cheio = nunca poda). */
    long worstDistance() {
        return size < K ? Long.MAX_VALUE : bestDistances[K - 1];
    }

    int fraudVotes() {
        int frauds = 0;
        for (int i = 0; i < size; i++) {
            frauds += bestLabels[i];
        }
        return frauds;
    }

    int scoreIndex() {
        int s = size;
        int frauds = fraudVotes();
        if (s == K) {
            return frauds;
        }
        if (s <= 0) {
            return 0;
        }
        float ratio = (float) frauds / (float) s;
        int idx = Math.round(ratio * K);
        if (idx < 0) return 0;
        if (idx > K) return K;
        return idx;
    }
}
