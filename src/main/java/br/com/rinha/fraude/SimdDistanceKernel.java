package br.com.rinha.fraude;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implementação SIMD via jdk.incubator.vector. Resolvida dinamicamente por
 * DistanceKernel — não a referencie diretamente em outros lugares para que
 * o fallback escalar continue funcionando em runtimes sem o módulo incubator.
 */
public final class SimdDistanceKernel {

    private static final VectorSpecies<Float> SPECIES = pickSpecies();

    private SimdDistanceKernel() {
    }

    private static VectorSpecies<Float> pickSpecies() {
        // Layout do vetor é PADDED_DIMENSIONS=16 floats. Preferimos SPECIES_256 (AVX2, 8 lanes)
        // que carrega exatamente 16 floats em 2 cargas e cobre Haswell/Mac Mini Late 2014.
        VectorSpecies<Float> preferred = FloatVector.SPECIES_256;
        if (preferred.length() <= Vectorizer.PADDED_DIMENSIONS) {
            return preferred;
        }
        return FloatVector.SPECIES_128;
    }

    public static float squared(float[] data, int offset, float[] query) {
        FloatVector a0 = FloatVector.fromArray(SPECIES, data, offset);
        FloatVector b0 = FloatVector.fromArray(SPECIES, query, 0);
        FloatVector d0 = a0.sub(b0);
        FloatVector acc = d0.mul(d0);

        int lane = SPECIES.length();
        if (lane < Vectorizer.PADDED_DIMENSIONS) {
            FloatVector a1 = FloatVector.fromArray(SPECIES, data, offset + lane);
            FloatVector b1 = FloatVector.fromArray(SPECIES, query, lane);
            FloatVector d1 = a1.sub(b1);
            acc = acc.add(d1.mul(d1));
        }
        // Os lanes 14 e 15 do query e dos vetores são zero (padding), portanto
        // não contribuem para a soma — não precisamos mascarar.
        return acc.reduceLanes(VectorOperators.ADD);
    }
}
