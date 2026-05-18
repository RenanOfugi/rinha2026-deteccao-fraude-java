package br.com.rinha.fraude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Executa requisições sintéticas contra o {@link DetectionEngine} antes
 * de marcar o servidor como pronto. Garante que:
 *   - O JIT promova o hot path para C2.
 *   - As páginas do índice mmap fiquem residentes no page cache.
 *   - As classes lazy-resolved (Vector API, MethodHandle) sejam inicializadas.
 *
 * Roda completamente em memória — não toca em sockets.
 */
final class Warmup {

    private static final byte[] SAMPLE_LEGIT = (
        "{\"id\":\"tx-0\",\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},"
            + "\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-003\",\"MERC-016\"]},"
            + "\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},"
            + "\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.23},"
            + "\"last_transaction\":null}"
    ).getBytes(StandardCharsets.UTF_8);

    private static final byte[] SAMPLE_FRAUD = (
        "{\"id\":\"tx-1\",\"transaction\":{\"amount\":9505.97,\"installments\":10,\"requested_at\":\"2026-03-14T05:15:12Z\"},"
            + "\"customer\":{\"avg_amount\":81.28,\"tx_count_24h\":20,\"known_merchants\":[\"MERC-008\",\"MERC-007\",\"MERC-005\"]},"
            + "\"merchant\":{\"id\":\"MERC-068\",\"mcc\":\"7802\",\"avg_amount\":54.86},"
            + "\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":952.27},"
            + "\"last_transaction\":null}"
    ).getBytes(StandardCharsets.UTF_8);

    private static final byte[] SAMPLE_WITH_LAST = (
        "{\"id\":\"tx-2\",\"transaction\":{\"amount\":120.50,\"installments\":1,\"requested_at\":\"2026-03-12T10:15:30Z\"},"
            + "\"customer\":{\"avg_amount\":95.0,\"tx_count_24h\":5,\"known_merchants\":[\"MERC-001\"]},"
            + "\"merchant\":{\"id\":\"MERC-001\",\"mcc\":\"5812\",\"avg_amount\":110.0},"
            + "\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":15.0},"
            + "\"last_transaction\":{\"timestamp\":\"2026-03-12T08:00:00Z\",\"km_from_current\":12.0}}"
    ).getBytes(StandardCharsets.UTF_8);

    private Warmup() {
    }

    static void run(DetectionEngine engine, int iterations) throws IOException {
        JsonRequestParser parser = new JsonRequestParser();
        MutableTransactionRequest request = new MutableTransactionRequest();
        float[] query = new float[Vectorizer.PADDED_DIMENSIONS];
        SearchScratch scratch = new SearchScratch(engine.index().clusterCount, engine.index().refineProbes);

        byte[][] samples = { SAMPLE_LEGIT, SAMPLE_FRAUD, SAMPLE_WITH_LAST };
        int sink = 0;
        for (int i = 0; i < iterations; i++) {
            byte[] sample = samples[i % samples.length];
            System.arraycopy(sample, 0, parser.buffer, 0, sample.length);
            parser.parseBuffer(sample.length);
            parser.parseRoot(request);
            sink ^= engine.evaluate(request, query, scratch);
        }
        if (sink == Integer.MIN_VALUE) {
            System.out.println("warmup sink: " + sink);
        }
    }
}
