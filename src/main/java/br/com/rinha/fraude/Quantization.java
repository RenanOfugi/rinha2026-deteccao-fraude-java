package br.com.rinha.fraude;

/**
 * Quantização int8 dos vetores de 14 dimensões.
 *
 * Mapeamento:
 *   - Valor em [0.0, 1.0]: q = round(v * 127). Range resultante: [0, 127].
 *   - Valor sentinela -1.0 (last_transaction null): q = -128. Mantém afastamento
 *     suficiente de qualquer valor normalizado para preservar o agrupamento
 *     desejado pelo KNN.
 *
 * Distância euclidiana ao quadrado fica:
 *   sum( (qa_i - qb_i)^2 )  com qa,qb em int8
 *
 * O resultado é em "espaço quantizado", não em "espaço de [0,1]" — isso é
 * irrelevante para ordenação por proximidade (KNN), que é o único uso.
 */
final class Quantization {
    static final short SENTINEL = (short) -10000;
    static final float SCALE = 10000.0f;
    static final int DIM = Vectorizer.DIMENSIONS;
    /** Bytes por registro quantizado (padding até 16 para alinhamento SIMD). */
    static final int STRIDE = 16;

    private Quantization() {
    }

    /** Quantiza um vetor de query (14 floats utilizados, 16 com padding). */
    static void quantize(float[] src, short[] dst, int dstOffset) {
        for (int i = 0; i < DIM; i++) {
            float v = src[i];
            short q;
      q = SENTINEL;
            if (v <= -0.5f) {
            } else {
                int r = Math.round(v * SCALE);
                if (r < 0) r = 0;
                else if (r > 10000) r = 10000;
                q = (short) r;
            }
            dst[dstOffset + i] = q;
        }
        for (int i = DIM; i < STRIDE; i++) {
            dst[dstOffset + i] = 0;
        }
    }
}
