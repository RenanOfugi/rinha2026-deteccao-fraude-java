package br.com.rinha.fraude;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Kernel de distância euclidiana ao quadrado entre vetor de referência (offset em float[])
 * e query (float[14] útil, 16 com padding). Tenta usar jdk.incubator.vector via reflexão
 * de MethodHandle (resolvido uma vez no carregamento da classe). Se o módulo não estiver
 * presente, usa fallback escalar com loop unrolled para 16 dims.
 */
final class DistanceKernel {

    @FunctionalInterface
    private interface Squared {
        float apply(float[] data, int offset, float[] query);
    }

    private static final Squared IMPL = resolve();

    private DistanceKernel() {
    }

    static float squared(float[] data, int offset, float[] query) {
        return IMPL.apply(data, offset, query);
    }

    private static Squared resolve() {
        try {
            Class<?> simd = Class.forName("br.com.rinha.fraude.SimdDistanceKernel");
            MethodHandle handle = MethodHandles.publicLookup().findStatic(
                simd,
                "squared",
                MethodType.methodType(float.class, float[].class, int.class, float[].class)
            );
            // Faz uma chamada de teste para confirmar que o módulo carrega.
            float[] probe = new float[Vectorizer.PADDED_DIMENSIONS];
            handle.invokeExact(probe, 0, probe);
            return (data, off, q) -> {
                try {
                    return (float) handle.invokeExact(data, off, q);
                } catch (Throwable t) {
                    return scalar(data, off, q);
                }
            };
        } catch (Throwable t) {
            return DistanceKernel::scalar;
        }
    }

    static float scalar(float[] data, int offset, float[] query) {
        float d0  = data[offset]      - query[0];
        float d1  = data[offset + 1]  - query[1];
        float d2  = data[offset + 2]  - query[2];
        float d3  = data[offset + 3]  - query[3];
        float d4  = data[offset + 4]  - query[4];
        float d5  = data[offset + 5]  - query[5];
        float d6  = data[offset + 6]  - query[6];
        float d7  = data[offset + 7]  - query[7];
        float d8  = data[offset + 8]  - query[8];
        float d9  = data[offset + 9]  - query[9];
        float d10 = data[offset + 10] - query[10];
        float d11 = data[offset + 11] - query[11];
        float d12 = data[offset + 12] - query[12];
        float d13 = data[offset + 13] - query[13];
        return d0*d0 + d1*d1 + d2*d2 + d3*d3
             + d4*d4 + d5*d5 + d6*d6 + d7*d7
             + d8*d8 + d9*d9 + d10*d10 + d11*d11
             + d12*d12 + d13*d13;
    }
}
