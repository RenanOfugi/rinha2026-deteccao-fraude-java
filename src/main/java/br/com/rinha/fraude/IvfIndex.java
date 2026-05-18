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
 * Índice IVF com vetores quantizados em int16 (scale=10000).
 *
 * Layout em disco:
 * - vectors.bin: para cada vetor, STRIDE shorts (int16). Agrupados por bucket.
 * - labels.bin:  1 byte por vetor (0=legit, 1=fraud).
 * - meta.bin:    header + centroides int16 + bucketOffsets[clusterCount+1].
 *
 * Distâncias em long: diff máx = 20000, diff² = 400_000_000,
 * soma de 14 dims = 5_600_000_000 > Integer.MAX_VALUE.
 */
final class IvfIndex implements AutoCloseable {

    final int clusterCount;
    final int totalVectors;
    final int probes;
    final short[] centroids;
    final long[] bucketOffsets;
    private final Arena arena;
    private final MemorySegment vectorSegment;
    private final MemorySegment labelSegment;

    private IvfIndex(
            int clusterCount,
            int totalVectors,
            int probes,
            short[] centroids,
            long[] bucketOffsets,
            Arena arena,
            MemorySegment vectorSegment,
            MemorySegment labelSegment) {
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
        try (DataInputStream input = new DataInputStream(
                Files.newInputStream(config.metadataFile()))) {

            int magic = input.readInt();
            int version = input.readInt();
            if (magic != IvfIndexBuilder.MAGIC || version != IvfIndexBuilder.VERSION) {
                throw new IOException("Indice incompatível (magic="
                        + Integer.toHexString(magic) + ", version=" + version + ")");
            }
            int dimensions = input.readInt();
            int stride = input.readInt();
            int clusterCount = input.readInt();
            int totalVectors = input.readInt();
            int probes = input.readInt();
            if (dimensions != Vectorizer.DIMENSIONS || stride != Quantization.STRIDE) {
                throw new IOException("Dimensoes incompatíveis no indice");
            }

            // centroids: clusterCount × STRIDE shorts
            short[] centroids = new short[clusterCount * Quantization.STRIDE];
            for (int i = 0; i < centroids.length; i++) {
                centroids[i] = input.readShort();
            }

            long[] bucketOffsets = new long[clusterCount + 1];
            for (int i = 0; i < bucketOffsets.length; i++) {
                bucketOffsets[i] = input.readLong();
            }

            Arena arena = Arena.ofShared();
            MemorySegment vectorSegment;
            MemorySegment labelSegment;
            try (FileChannel vc = FileChannel.open(config.vectorsFile(), StandardOpenOption.READ);
                 FileChannel lc = FileChannel.open(config.labelsFile(), StandardOpenOption.READ)) {
                vectorSegment = vc.map(FileChannel.MapMode.READ_ONLY, 0L, vc.size(), arena);
                labelSegment  = lc.map(FileChannel.MapMode.READ_ONLY, 0L, lc.size(), arena);
            }

            int effectiveProbes = Math.max(1, Math.min(config.ivfProbes, clusterCount));
            prefetch(vectorSegment);
            prefetch(labelSegment);

            return new IvfIndex(clusterCount, totalVectors, effectiveProbes,
                    centroids, bucketOffsets, arena, vectorSegment, labelSegment);
        }
    }

    int search(float[] queryFloat, SearchScratch scratch) {
        scratch.reset();
        short[] queryQuant = scratch.queryQuant;
        Quantization.quantize(queryFloat, queryQuant, 0);

        // Fase 1: selecionar os `probes` centroides mais próximos
        final short[] cents = centroids;
        final int clusters = clusterCount;
        for (int centroidId = 0; centroidId < clusters; centroidId++) {
            long distance = QuantDistanceKernel.squared(
                    cents, centroidId * Quantization.STRIDE, queryQuant);
            scratch.offerCentroid(centroidId, distance);
        }

        // Fase 2: varrer os buckets selecionados em chunks
        final int[] probeIds = scratch.bestCentroidIds;
        final short[] bucketBuffer = scratch.quantBucketBuffer;
        final byte[] labelBuffer = scratch.labelBuffer;
        final int chunkCap = SearchScratch.BUCKET_CHUNK;

        for (int i = 0; i < probeIds.length; i++) {
            int centroidId = probeIds[i];
            if (centroidId < 0) break;

            long start = bucketOffsets[centroidId];
            long end   = bucketOffsets[centroidId + 1];
            long count = end - start;
            if (count <= 0L) continue;

            long rOffset = 0L;
            long remaining = count;
            while (remaining > 0L) {
                int chunk = (int) Math.min(remaining, chunkCap);

                // Offset em bytes no vectorSegment:
                // cada vetor ocupa STRIDE shorts = STRIDE * Short.BYTES bytes
                long byteOffset = (start + rOffset) * Quantization.STRIDE * Short.BYTES;
                MemorySegment.copy(vectorSegment, ValueLayout.JAVA_SHORT,
                        byteOffset, bucketBuffer, 0,  chunk * Quantization.STRIDE);

                MemorySegment.copy(labelSegment, ValueLayout.JAVA_BYTE,
                        start + rOffset, labelBuffer, 0, chunk);

                for (int v = 0; v < chunk; v++) {
                    long distance = QuantDistanceKernel.squared(
                            bucketBuffer, v * Quantization.STRIDE, queryQuant);
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
        long acc = 0L;
        // Toca uma short por página (4096 bytes = 2048 shorts)
        for (long off = 0; off < size - 1; off += 4096L) {
            acc += segment.get(ValueLayout.JAVA_SHORT, off);
        }
        if (acc == Long.MIN_VALUE) System.out.print(""); // sink para evitar eliminação pelo JIT
    }
}
