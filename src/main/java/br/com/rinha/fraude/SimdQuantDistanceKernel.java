package br.com.rinha.fraude;

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
}
