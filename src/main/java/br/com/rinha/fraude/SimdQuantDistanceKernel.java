package br.com.rinha.fraude;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Kernel SIMD para distância int16. O stride tem 16 shorts: 14 dimensões úteis
 * e 2 posições de padding zeradas. Cada metade de 8 shorts é promovida para int
 * antes do subtract/multiply; a redução final usa long porque a soma pode passar
 * de Integer.MAX_VALUE com SCALE=10000.
 */
public final class SimdQuantDistanceKernel {

    private static final VectorSpecies<Short> S_SPECIES = ShortVector.SPECIES_128; // 8 shorts
    private static final VectorSpecies<Integer> I_SPECIES = IntVector.SPECIES_256; // 8 ints

    private SimdQuantDistanceKernel() {
    }

    public static long squared(short[] data, int offset, short[] query) {
        IntVector lo = diff(data, offset, query, 0);
        IntVector hi = diff(data, offset + S_SPECIES.length(), query, S_SPECIES.length());
        IntVector squareLo = lo.mul(lo);
        IntVector squareHi = hi.mul(hi);
        return squareLo.reduceLanesToLong(VectorOperators.ADD)
            + squareHi.reduceLanesToLong(VectorOperators.ADD);
    }

    private static IntVector diff(short[] data, int dataOffset, short[] query, int queryOffset) {
        ShortVector va = ShortVector.fromArray(S_SPECIES, data, dataOffset);
        ShortVector vb = ShortVector.fromArray(S_SPECIES, query, queryOffset);
        IntVector ia = (IntVector) va.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        IntVector ib = (IntVector) vb.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        return ia.sub(ib);
    }

    /**
     * Distância² lendo o vetor de dados DIRETO do MemorySegment (mmap), sem
     * cópia intermediária para um short[]. byteOffset é o offset em bytes do
     * início do registro (STRIDE shorts). O mmap é little-endian.
     */
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    public static long squaredFromSegment(MemorySegment data, long byteOffset, short[] query) {
        IntVector lo = diffSeg(data, byteOffset, query, 0);
        IntVector hi = diffSeg(data, byteOffset + S_SPECIES.length() * 2L, query, S_SPECIES.length());
        IntVector squareLo = lo.mul(lo);
        IntVector squareHi = hi.mul(hi);
        return squareLo.reduceLanesToLong(VectorOperators.ADD)
            + squareHi.reduceLanesToLong(VectorOperators.ADD);
    }

    private static IntVector diffSeg(MemorySegment data, long byteOffset, short[] query, int queryOffset) {
        ShortVector va = ShortVector.fromMemorySegment(S_SPECIES, data, byteOffset, LE);
        ShortVector vb = ShortVector.fromArray(S_SPECIES, query, queryOffset);
        IntVector ia = (IntVector) va.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        IntVector ib = (IntVector) vb.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        return ia.sub(ib);
    }
}
