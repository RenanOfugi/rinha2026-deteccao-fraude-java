package br.com.rinha.fraude;

final class DetectionEngine {
    private final Vectorizer vectorizer;
    private final IvfIndex index;

    DetectionEngine(Vectorizer vectorizer, IvfIndex index) {
        this.vectorizer = vectorizer;
        this.index = index;
    }

    int evaluate(MutableTransactionRequest request, float[] queryVector, SearchScratch scratch) {
        vectorizer.fillQueryVector(request, queryVector);
        return index.search(queryVector, scratch);
    }

    IvfIndex index() {
        return index;
    }
}
