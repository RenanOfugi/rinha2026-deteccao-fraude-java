package br.com.rinha.fraude;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Kernel SIMD para distância int8. Carrega 16 bytes (1 stride), promove para
 * short para evitar overflow no subtract, multiplica em short (diff² ≤ 65025
 * cabe em short com sinal — não, máx 65025 > 32767. Por isso promovemos para
 * int antes do multiply.).
 */
public final class SimdQuantDistanceKernel {

    private static final VectorSpecies<Byte> B_SPECIES = ByteVector.SPECIES_128; // 16 bytes
    private static final VectorSpecies<Short> S_SPECIES = ShortVector.SPECIES_256; // 16 shorts
    private static final VectorSpecies<Integer> I_SPECIES = IntVector.SPECIES_256; // 8 ints

    private SimdQuantDistanceKernel() {
    }

    public static int squared(byte[] data, int offset, byte[] query) {
        ByteVector va = ByteVector.fromArray(B_SPECIES, data, offset);
        ByteVector vb = ByteVector.fromArray(B_SPECIES, query, 0);
        // Promove ambos para short (cabe sem perda; sinal preservado).
        ShortVector sa = (ShortVector) va.convertShape(VectorOperators.B2S, S_SPECIES, 0);
        ShortVector sb = (ShortVector) vb.convertShape(VectorOperators.B2S, S_SPECIES, 0);
        ShortVector diffShort = sa.sub(sb);
        // Para evitar overflow em diff*diff (máx 65025 > short_max 32767),
        // separamos em duas metades de 8 lanes e operamos em int.
        IntVector lo = (IntVector) diffShort.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        IntVector hi = (IntVector) diffShort.convertShape(VectorOperators.S2I, I_SPECIES, 1);
        IntVector squareLo = lo.mul(lo);
        IntVector squareHi = hi.mul(hi);
        // Lanes 14 e 15 são padding e devem somar 0 (query e centroide
        // gravam 0 nessas posições durante a quantização).
        return squareLo.add(squareHi).reduceLanes(VectorOperators.ADD);
    }
}
