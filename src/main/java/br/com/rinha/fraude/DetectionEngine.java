package br.com.rinha.fraude;

final class DetectionEngine {
    private final Vectorizer vectorizer;
    private final IvfIndex index;

    DetectionEngine(Vectorizer vectorizer, IvfIndex index) {
        this.vectorizer = vectorizer;
        this.index = index;
    }

    int evaluate(MutableTransactionRequest request, float[] queryVector, SearchScratch scratch) {
        long t = LatencyStats.startIfEnabled();
        vectorizer.fillQueryVector(request, queryVector);
        LatencyStats.record(LatencyStats.Stage.VECTORIZE, t);
        return index.search(queryVector, scratch);
    }

    IvfIndex index() {
        return index;
    }
}
