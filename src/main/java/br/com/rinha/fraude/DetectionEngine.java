package br.com.rinha.fraude;

import java.io.IOException;

/**
 * Cola Vectorizer + índice IVF particionado por tag de domínio.
 *
 * O índice é dividido em 4 partições por (unknown_merchant << 1) | has_last_tx.
 * Os 5 vizinhos mais próximos de uma query compartilham a mesma tag (validado:
 * 100% das amostras), então rotear a busca para a partição da tag é exato e
 * varre ~1/4 dos vetores.
 */
final class DetectionEngine {
    private final Vectorizer vectorizer;
    private final IvfIndex[] partitions;   // indexado por tag 0..3
    private final IvfIndex largest;        // maior clusterCount (dimensiona o scratch)

    DetectionEngine(Vectorizer vectorizer, IvfIndex[] partitions) {
        this.vectorizer = vectorizer;
        this.partitions = partitions;
        IvfIndex max = partitions[0];
        for (IvfIndex p : partitions) {
            if (p != null && p.clusterCount > max.clusterCount) max = p;
        }
        this.largest = max;
    }

    static DetectionEngine load(AppConfig config, Vectorizer vectorizer) throws IOException {
        IvfIndex[] parts = new IvfIndex[AppConfig.N_PARTITIONS];
        for (int tag = 0; tag < AppConfig.N_PARTITIONS; tag++) {
            parts[tag] = IvfIndex.loadPartition(config, tag);
        }
        return new DetectionEngine(vectorizer, parts);
    }

    int evaluate(MutableTransactionRequest request, float[] queryVector, SearchScratch scratch) {
        vectorizer.fillQueryVector(request, queryVector);
        int tag = tagOf(queryVector);
        return partitions[tag].search(queryVector, scratch);
    }

    /** Tag de domínio: bit1 = unknown_merchant (v[11]>0.5), bit0 = has_last_tx (v[5]>=0). */
    static int tagOf(float[] queryVector) {
        int unknown = queryVector[11] > 0.5f ? 1 : 0;
        int hasLast = queryVector[5] >= 0.0f ? 1 : 0;
        return (unknown << 1) | hasLast;
    }

    /** Partição com maior clusterCount — usada para dimensionar o SearchScratch. */
    IvfIndex index() {
        return largest;
    }

    void close() {
        for (IvfIndex p : partitions) {
            if (p != null) p.close();
        }
    }
}
