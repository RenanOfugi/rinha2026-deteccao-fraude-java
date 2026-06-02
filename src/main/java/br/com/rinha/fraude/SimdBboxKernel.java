package br.com.rinha.fraude;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Kernel SIMD do lower-bound da bounding box: para cada dimensão, a folga até a
 * caixa é gap = max(0, lo - q, q - hi), e o lower bound é Σ gap². Branchless e
 * vetorizável. Os arrays bboxMin/bboxMax têm stride STRIDE=16 (14 dims úteis + 2
 * de padding zerado); no padding lo=hi=0 e q=0, então gap=0 e não contamina.
 *
 * Layout: 16 shorts por cluster, processados em duas metades de 8 (SPECIES_128),
 * promovidas a int (SPECIES_256) para o quadrado/soma — idêntico ao padrão do
 * SimdQuantDistanceKernel. Redução final em long porque Σ gap² pode passar de
 * Integer.MAX_VALUE com SCALE=10000.
 */
public final class SimdBboxKernel {

    private static final VectorSpecies<Short> S_SPECIES = ShortVector.SPECIES_128; // 8 shorts
    private static final VectorSpecies<Integer> I_SPECIES = IntVector.SPECIES_256; // 8 ints
    private static final IntVector ZERO = IntVector.zero(I_SPECIES);

    private SimdBboxKernel() {
    }

    public static long lowerBound(short[] bboxMin, short[] bboxMax, int base, short[] query) {
        IntVector gapLo = gap(bboxMin, bboxMax, base, query, 0);
        IntVector gapHi = gap(bboxMin, bboxMax, base + S_SPECIES.length(), query, S_SPECIES.length());
        IntVector sqLo = gapLo.mul(gapLo);
        IntVector sqHi = gapHi.mul(gapHi);
        return sqLo.reduceLanesToLong(VectorOperators.ADD)
            + sqHi.reduceLanesToLong(VectorOperators.ADD);
    }

    /** gap = max(0, lo - q, q - hi) por lane, em int. */
    private static IntVector gap(short[] bboxMin, short[] bboxMax, int boxOffset, short[] query, int qOffset) {
        ShortVector lo = ShortVector.fromArray(S_SPECIES, bboxMin, boxOffset);
        ShortVector hi = ShortVector.fromArray(S_SPECIES, bboxMax, boxOffset);
        ShortVector q = ShortVector.fromArray(S_SPECIES, query, qOffset);
        IntVector loI = (IntVector) lo.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        IntVector hiI = (IntVector) hi.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        IntVector qI  = (IntVector) q.convertShape(VectorOperators.S2I, I_SPECIES, 0);
        // q abaixo da caixa: lo - q > 0 ; q acima: q - hi > 0 ; dentro: ambos <= 0.
        IntVector below = loI.sub(qI);
        IntVector above = qI.sub(hiI);
        return below.max(above).max(ZERO);
    }
}
