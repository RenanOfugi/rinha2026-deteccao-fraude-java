package br.com.rinha.fraude;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Índice IVF com vetores quantizados em int8.
 *
 * Layout em memória:
 *   - vectors.bin: para cada vetor, {@link Quantization#STRIDE} bytes em int8.
 *                  Vetores são agrupados por bucket (cluster).
 *   - labels.bin: 1 byte por vetor (0=legit, 1=fraud), na mesma ordem.
 *   - meta.bin: header + centroides quantizados + bucketOffsets[clusterCount+1].
 *
 * Operação de busca:
 *   1. Quantiza a query int8.
 *   2. Para cada centroide, calcula distância int8 e seleciona os `nprobe` melhores.
 *   3. Para cada bucket sondado, percorre os vetores e calcula distância int8.
 *   4. Mantém um heap top-K via inserção linear (K=5).
 */
final class IvfIndex implements AutoCloseable {

    final int clusterCount;
    final int totalVectors;
    final int probes;
    final byte[] centroids;
    final long[] bucketOffsets;
    private final Arena arena;
    private final MemorySegment vectorSegment;
    private final MemorySegment labelSegment;

    private IvfIndex(
        int clusterCount,
        int totalVectors,
        int probes,
        byte[] centroids,
        long[] bucketOffsets,
        Arena arena,
        MemorySegment vectorSegment,
        MemorySegment labelSegment
    ) {
        this.clusterCount = clusterCount;
        this.totalVectors = totalVectors;
        this.probes = probes;
        this.centroids = centroids;
        this.bucketOffsets = bucketOffsets;
        this.arena = arena;
        this.vectorSegment = vectorSegment;
        this.labelSegment = labelSegment;
    }

    static IvfIndex load(AppConfig config) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(config.metadataFile()))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != IvfIndexBuilder.MAGIC || version != IvfIndexBuilder.VERSION) {
                throw new IOException("Indice em formato incompatível (magic=" + Integer.toHexString(magic) + ", version=" + version + "). Rebuild necessário.");
            }
            int dimensions = input.readInt();
            int stride = input.readInt();
            int clusterCount = input.readInt();
            int totalVectors = input.readInt();
            int probes = input.readInt();
            if (dimensions != Vectorizer.DIMENSIONS || stride != Quantization.STRIDE) {
                throw new IOException("Dimensoes incompatíveis no indice");
            }
            byte[] centroids = new byte[clusterCount * stride];
            input.readFully(centroids);
            long[] bucketOffsets = new long[clusterCount + 1];
            for (int i = 0; i < bucketOffsets.length; i++) {
                bucketOffsets[i] = input.readLong();
            }
            Arena arena = Arena.ofShared();
            MemorySegment vectorSegment;
            MemorySegment labelSegment;
            try (FileChannel vectorChannel = FileChannel.open(config.vectorsFile(), StandardOpenOption.READ);
                 FileChannel labelChannel = FileChannel.open(config.labelsFile(), StandardOpenOption.READ)) {
                vectorSegment = vectorChannel.map(FileChannel.MapMode.READ_ONLY, 0L, vectorChannel.size(), arena);
                labelSegment = labelChannel.map(FileChannel.MapMode.READ_ONLY, 0L, labelChannel.size(), arena);
            }
            int effectiveProbes = Math.max(1, Math.min(config.ivfProbes, clusterCount));
            prefetch(vectorSegment);
            prefetch(labelSegment);
            return new IvfIndex(
                clusterCount,
                totalVectors,
                effectiveProbes,
                centroids,
                bucketOffsets,
                arena,
                vectorSegment,
                labelSegment
            );
        }
    }

    int search(float[] queryFloat, SearchScratch scratch) {
        scratch.reset();
        byte[] queryQuant = scratch.queryQuant;
        Quantization.quantize(queryFloat, queryQuant, 0);

        final int clusters = clusterCount;
        final byte[] cents = centroids;
        for (int centroidId = 0; centroidId < clusters; centroidId++) {
            int distance = QuantDistanceKernel.squared(cents, centroidId * Quantization.STRIDE, queryQuant);
            scratch.offerCentroid(centroidId, distance);
        }

        final int[] probeIds = scratch.bestCentroidIds;
        final byte[] bucketBuffer = scratch.quantBucketBuffer;
        final byte[] labelBuffer = scratch.labelBuffer;
        final int chunkCap = SearchScratch.BUCKET_CHUNK;
        for (int i = 0; i < probeIds.length; i++) {
            int centroidId = probeIds[i];
            if (centroidId < 0) {
                break;
            }
            long start = bucketOffsets[centroidId];
            long end = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;

            long rOffset = 0L;
            long remaining = count;
            while (remaining > 0L) {
                int chunk = (int) Math.min(remaining, (long) chunkCap);
                int bytes = chunk * Quantization.STRIDE;
                long byteOffset = (start + rOffset) * Quantization.STRIDE;
                MemorySegment.copy(vectorSegment, ValueLayout.JAVA_BYTE, byteOffset, bucketBuffer, 0, bytes);
                MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE, start + rOffset, labelBuffer, 0, chunk);
                for (int v = 0; v < chunk; v++) {
                    int distance = QuantDistanceKernel.squared(bucketBuffer, v * Quantization.STRIDE, queryQuant);
                    scratch.offerNeighbor(distance, labelBuffer[v]);
                }
                rOffset += chunk;
                remaining -= chunk;
            }
        }
        return scratch.scoreIndex();
    }

    @Override
    public void close() {
        arena.close();
    }

    private static void prefetch(MemorySegment segment) {
        long size = segment.byteSize();
        long step = 4096L;
        long acc = 0L;
        for (long off = 0; off < size; off += step) {
            acc += segment.get(ValueLayout.JAVA_BYTE, off);
        }
        if (acc == Long.MIN_VALUE) {
            System.out.println("prefetch sink: " + acc);
        }
    }
}
