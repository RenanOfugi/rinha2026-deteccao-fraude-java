package br.com.rinha.fraude;

/**
 * Distância euclidiana ao quadrado entre dois vetores int16 (range [-10000, 10000]).
 *
 * Diferença máxima por componente: 20000 → diff² ≤ 400_000_000.
 * Soma de 14 componentes: máx 5_600_000_000 — requer long (estoura int).
 *
 * Resolve uma única vez no static init se o SimdQuantDistanceKernel está disponível
 * e funcional (USE_SIMD). O hot path é uma chamada estática direta — sem
 * MethodHandle, sem lambda, sem try/catch — para que o JIT consiga inline agressivo
 * e o SIMD interno fique exposto para escalonamento de instruções.
 */
final class QuantDistanceKernel {

    private static final boolean USE_SIMD = probeSimd();

    private QuantDistanceKernel() {
    }

    static long squared(short[] data, int offset, short[] query) {
        return USE_SIMD
            ? SimdQuantDistanceKernel.squared(data, offset, query)
            : scalar(data, offset, query);
    }

    /** Distância² lendo o vetor direto do MemorySegment (sem cópia). No fallback
     *  escalar, copia o registro para um buffer thread-local e usa scalar(). */
    static long squaredFromSegment(java.lang.foreign.MemorySegment data, long byteOffset, short[] query) {
        if (USE_SIMD) {
            return SimdQuantDistanceKernel.squaredFromSegment(data, byteOffset, query);
        }
        short[] tmp = SCALAR_TMP.get();
        java.lang.foreign.MemorySegment.copy(data, java.lang.foreign.ValueLayout.JAVA_SHORT,
                byteOffset, tmp, 0, Quantization.STRIDE);
        return scalar(tmp, 0, query);
    }

    private static final ThreadLocal<short[]> SCALAR_TMP =
            ThreadLocal.withInitial(() -> new short[Quantization.STRIDE]);

    private static boolean probeSimd() {
        try {
            // Valida array-based e segment-based contra o escalar.
            short[] a = { 10, -20, 30, 40, -50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 0, 0 };
            short[] q = {  5,  15, 25, 35,  45, 55, 65, 75, 85,  95, 105, 115, 125, 135, 0, 0 };
            long expected = scalar(a, 0, q);
            if (SimdQuantDistanceKernel.squared(a, 0, q) != expected) return false;
            try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                java.lang.foreign.MemorySegment seg =
                        arena.allocate((long) Quantization.STRIDE * Short.BYTES);
                java.lang.foreign.MemorySegment.copy(a, 0, seg,
                        java.lang.foreign.ValueLayout.JAVA_SHORT, 0, Quantization.STRIDE);
                if (SimdQuantDistanceKernel.squaredFromSegment(seg, 0, q) != expected) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static long scalar(short[] data, int offset, short[] query) {
        // Diferenças em int para evitar overflow intermediário de short
        long d0  = data[offset]      - query[0];
        long d1  = data[offset + 1]  - query[1];
        long d2  = data[offset + 2]  - query[2];
        long d3  = data[offset + 3]  - query[3];
        long d4  = data[offset + 4]  - query[4];
        long d5  = data[offset + 5]  - query[5];
        long d6  = data[offset + 6]  - query[6];
        long d7  = data[offset + 7]  - query[7];
        long d8  = data[offset + 8]  - query[8];
        long d9  = data[offset + 9]  - query[9];
        long d10 = data[offset + 10] - query[10];
        long d11 = data[offset + 11] - query[11];
        long d12 = data[offset + 12] - query[12];
        long d13 = data[offset + 13] - query[13];
        return d0*d0 + d1*d1 + d2*d2  + d3*d3
             + d4*d4 + d5*d5 + d6*d6  + d7*d7
             + d8*d8 + d9*d9 + d10*d10 + d11*d11
             + d12*d12 + d13*d13;
    }
}
