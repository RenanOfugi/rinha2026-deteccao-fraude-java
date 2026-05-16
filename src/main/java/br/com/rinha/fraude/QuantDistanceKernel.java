package br.com.rinha.fraude;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Distância euclidiana ao quadrado entre dois vetores int8 (range [-128, 127]).
 *
 * Diferença máxima por componente: 255 → diff² ≤ 65025. Soma de 14 componentes
 * cabe folgadamente em int (máx ≈ 910350). Sem overflow.
 *
 * Tenta resolver via {@link SimdQuantDistanceKernel} (Vector API); se falhar,
 * usa fallback escalar com loop totalmente unrolled.
 */
final class QuantDistanceKernel {

    @FunctionalInterface
    private interface Squared {
        int apply(byte[] data, int offset, byte[] query);
    }

    private static final Squared IMPL = resolve();

    private QuantDistanceKernel() {
    }

    static int squared(byte[] data, int offset, byte[] query) {
        return IMPL.apply(data, offset, query);
    }

    private static Squared resolve() {
        try {
            Class<?> simd = Class.forName("br.com.rinha.fraude.SimdQuantDistanceKernel");
            MethodHandle handle = MethodHandles.publicLookup().findStatic(
                simd,
                "squared",
                MethodType.methodType(int.class, byte[].class, int.class, byte[].class)
            );
            byte[] probe = new byte[Quantization.STRIDE];
            handle.invokeExact(probe, 0, probe);
            return (data, off, q) -> {
                try {
                    return (int) handle.invokeExact(data, off, q);
                } catch (Throwable t) {
                    return scalar(data, off, q);
                }
            };
        } catch (Throwable t) {
            return QuantDistanceKernel::scalar;
        }
    }

    static int scalar(byte[] data, int offset, byte[] query) {
        int d0  = data[offset]      - query[0];
        int d1  = data[offset + 1]  - query[1];
        int d2  = data[offset + 2]  - query[2];
        int d3  = data[offset + 3]  - query[3];
        int d4  = data[offset + 4]  - query[4];
        int d5  = data[offset + 5]  - query[5];
        int d6  = data[offset + 6]  - query[6];
        int d7  = data[offset + 7]  - query[7];
        int d8  = data[offset + 8]  - query[8];
        int d9  = data[offset + 9]  - query[9];
        int d10 = data[offset + 10] - query[10];
        int d11 = data[offset + 11] - query[11];
        int d12 = data[offset + 12] - query[12];
        int d13 = data[offset + 13] - query[13];
        return d0*d0 + d1*d1 + d2*d2 + d3*d3
             + d4*d4 + d5*d5 + d6*d6 + d7*d7
             + d8*d8 + d9*d9 + d10*d10 + d11*d11
             + d12*d12 + d13*d13;
    }
}
