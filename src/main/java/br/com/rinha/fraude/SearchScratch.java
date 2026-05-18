package br.com.rinha.fraude;

import java.util.Arrays;

final class SearchScratch {
    static final int K = 5;
    static final int BUCKET_CHUNK = 1024;

    final long[] bestDistances = new long[K];
    final byte[] bestLabels = new byte[K];
    final long[] bestCentroidDistances;
    final int[] bestCentroidIds;
    final short[] quantBucketBuffer = new short[BUCKET_CHUNK * Quantization.STRIDE];
    final byte[] labelBuffer = new byte[BUCKET_CHUNK];
    final short[] queryQuant = new short[Quantization.STRIDE];
    int size;

    SearchScratch(int clusterCount, int probes) {
        this.bestCentroidDistances = new long[probes];
        this.bestCentroidIds = new int[probes];
        Arrays.fill(bestDistances, Long.MAX_VALUE);
        Arrays.fill(bestCentroidDistances, Long.MAX_VALUE);
    }

    void reset() {
        Arrays.fill(bestDistances, Long.MAX_VALUE);
        Arrays.fill(bestLabels, (byte) 0);
        Arrays.fill(bestCentroidDistances, Long.MAX_VALUE);
        Arrays.fill(bestCentroidIds, -1);
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

    void offerCentroid(int centroidId, long distance) {
        int last = bestCentroidDistances.length - 1;
        if (distance >= bestCentroidDistances[last]) {
            return;
        }
        int insertAt = last;
        while (insertAt > 0 && distance < bestCentroidDistances[insertAt - 1]) {
            bestCentroidDistances[insertAt] = bestCentroidDistances[insertAt - 1];
            bestCentroidIds[insertAt] = bestCentroidIds[insertAt - 1];
            insertAt--;
        }
        bestCentroidDistances[insertAt] = distance;
        bestCentroidIds[insertAt] = centroidId;
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
