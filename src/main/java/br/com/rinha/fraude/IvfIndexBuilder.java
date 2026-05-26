package br.com.rinha.fraude;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

final class IvfIndexBuilder {
  static final int MAGIC = 0x49564632;
  static final int VERSION = 2;

  private IvfIndexBuilder() {
  }

  static void build(AppConfig config) throws IOException {
    Files.createDirectories(config.indexDir);
    float[] centroids = trainCentroids(config.referencesFile(), config.ivfClusters,
        config.ivfSampleSize, config.kmeansIterations);
    materializeIndex(config, centroids);
  }

  private static float[] trainCentroids(Path referencesFile, int clusters,
      int sampleSize, int iterations) throws IOException {
    List<float[]> sample = new ArrayList<>(sampleSize);
    try (ReferenceReader reader = new ReferenceReader(referencesFile)) {
      while (sample.size() < sampleSize && reader.next()) {
        sample.add(Arrays.copyOf(reader.vector(), Vectorizer.PADDED_DIMENSIONS));
      }
    }
    if (sample.isEmpty())
      throw new IOException("Dataset de referencias vazio");

    int actualClusters = Math.min(clusters, sample.size());
    float[] centroids = initKMeansPlusPlus(sample, actualClusters);

    int[] assignments = new int[sample.size()];
    float[] sums = new float[centroids.length];
    int[] counts = new int[actualClusters];
    for (int iteration = 0; iteration < iterations; iteration++) {
      Arrays.fill(sums, 0.0f);
      Arrays.fill(counts, 0);
      for (int i = 0; i < sample.size(); i++) {
        float[] vector = sample.get(i);
        int centroidId = nearestCentroid(vector, centroids, actualClusters);
        assignments[i] = centroidId;
        counts[centroidId]++;
        int base = centroidId * Vectorizer.PADDED_DIMENSIONS;
        for (int d = 0; d < Vectorizer.PADDED_DIMENSIONS; d++) {
          sums[base + d] += vector[d];
        }
      }
      for (int centroidId = 0; centroidId < actualClusters; centroidId++) {
        if (counts[centroidId] == 0)
          continue;
        int base = centroidId * Vectorizer.PADDED_DIMENSIONS;
        float inv = 1.0f / counts[centroidId];
        for (int d = 0; d < Vectorizer.PADDED_DIMENSIONS; d++) {
          centroids[base + d] = sums[base + d] * inv;
        }
      }
    }
    return centroids;
  }

  private static float[] initKMeansPlusPlus(List<float[]> sample, int k) {
    float[] centroids = new float[k * Vectorizer.PADDED_DIMENSIONS];
    java.util.Random rng = new java.util.Random(42L);

    int first = rng.nextInt(sample.size());
    System.arraycopy(sample.get(first), 0, centroids, 0, Vectorizer.PADDED_DIMENSIONS);

    float[] minDist = new float[sample.size()];
    Arrays.fill(minDist, Float.MAX_VALUE);

    for (int c = 1; c < k; c++) {
      int prevBase = (c - 1) * Vectorizer.PADDED_DIMENSIONS;
      double totalWeight = 0.0;
      for (int i = 0; i < sample.size(); i++) {
        float d = DistanceKernel.squared(centroids, prevBase, sample.get(i));
        if (d < minDist[i])
          minDist[i] = d;
        totalWeight += minDist[i];
      }
      double threshold = rng.nextDouble() * totalWeight;
      double cumulative = 0.0;
      int chosen = sample.size() - 1;
      for (int i = 0; i < sample.size(); i++) {
        cumulative += minDist[i];
        if (cumulative >= threshold) {
          chosen = i;
          break;
        }
      }
      System.arraycopy(sample.get(chosen), 0, centroids,
          c * Vectorizer.PADDED_DIMENSIONS, Vectorizer.PADDED_DIMENSIONS);
    }
    return centroids;
  }

  private static void materializeIndex(AppConfig config, float[] centroids) throws IOException {
    int clusterCount = centroids.length / Vectorizer.PADDED_DIMENSIONS;
    Path tempDir = config.indexDir.resolve("tmp");
    Files.createDirectories(tempDir);

    // DataOutputStream para escrever shorts corretamente
    DataOutputStream[] vectorBuckets = new DataOutputStream[clusterCount];
    BufferedOutputStream[] labelBuckets = new BufferedOutputStream[clusterCount];
    Path[] vectorBucketFiles = new Path[clusterCount];
    Path[] labelBucketFiles = new Path[clusterCount];
    long[] bucketSizes = new long[clusterCount];

    for (int i = 0; i < clusterCount; i++) {
      vectorBucketFiles[i] = tempDir.resolve("bucket-" + i + ".vec");
      labelBucketFiles[i] = tempDir.resolve("bucket-" + i + ".lbl");
      vectorBuckets[i] = new DataOutputStream(new BufferedOutputStream(
          Files.newOutputStream(vectorBucketFiles[i],
              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
      labelBuckets[i] = new BufferedOutputStream(
          Files.newOutputStream(labelBucketFiles[i],
              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    short[] quantBuffer = new short[Quantization.STRIDE];
    int totalVectors = 0;
    int overflowedAssignments = 0;
    final int maxBucketSize = config.ivfMaxBucketSize;
    // Scratch arrays para nearestAvailableCentroid — reusados, evitam alocação no loop.
    float[] distScratch = new float[clusterCount];
    int[] orderScratch = new int[clusterCount];
    try (ReferenceReader reader = new ReferenceReader(config.referencesFile())) {
      while (reader.next()) {
        float[] vector = reader.vector();
        int centroidId = nearestAvailableCentroid(
            vector, centroids, clusterCount, bucketSizes, maxBucketSize,
            distScratch, orderScratch);
        // Conta vetores que tiveram que ir para 2º/3º/etc por causa do cap.
        int nearestId = orderScratch[0];
        if (centroidId != nearestId) {
          overflowedAssignments++;
        }
        Quantization.quantize(vector, quantBuffer, 0);

        ByteBuffer buf = ByteBuffer
            .allocate(Quantization.STRIDE * Short.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

        for (int s = 0; s < Quantization.STRIDE; s++) {
          buf.putShort(quantBuffer[s]);
        }

        vectorBuckets[centroidId].write(buf.array());
        labelBuckets[centroidId].write(reader.label());
        bucketSizes[centroidId]++;
        totalVectors++;
      }
    } finally {

      for (DataOutputStream stream : vectorBuckets) {
        stream.close();
      }

      for (BufferedOutputStream stream : labelBuckets) {
        stream.close();
      }
    }

    long[] bucketOffsets = new long[clusterCount + 1];
    long maxBucket = 0L;
    long minBucket = Long.MAX_VALUE;
    for (int i = 0; i < clusterCount; i++) {
      bucketOffsets[i + 1] = bucketOffsets[i] + bucketSizes[i];
      if (bucketSizes[i] > maxBucket) maxBucket = bucketSizes[i];
      if (bucketSizes[i] < minBucket) minBucket = bucketSizes[i];
    }
    System.out.println("Balanced k-means: total=" + totalVectors
        + " overflowed=" + overflowedAssignments
        + " (" + String.format("%.2f", 100.0 * overflowedAssignments / totalVectors) + "%)"
        + " maxBucket=" + maxBucket
        + " minBucket=" + minBucket
        + " cap=" + maxBucketSize);

    try (BufferedOutputStream vectorsOut = new BufferedOutputStream(
        Files.newOutputStream(config.vectorsFile(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        BufferedOutputStream labelsOut = new BufferedOutputStream(
            Files.newOutputStream(config.labelsFile(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
      for (int i = 0; i < clusterCount; i++) {
        Files.copy(vectorBucketFiles[i], vectorsOut);
        Files.copy(labelBucketFiles[i], labelsOut);
      }
    }

    // Quantiza centroides para int16
    short[] quantCentroids = new short[clusterCount * Quantization.STRIDE];
    for (int i = 0; i < clusterCount; i++) {
      int base = i * Vectorizer.PADDED_DIMENSIONS;
      float[] tmp = new float[Vectorizer.PADDED_DIMENSIONS];
      System.arraycopy(centroids, base, tmp, 0, Vectorizer.PADDED_DIMENSIONS);
      Quantization.quantize(tmp, quantCentroids, i * Quantization.STRIDE);
    }

    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
        Files.newOutputStream(config.metadataFile(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      output.writeInt(Vectorizer.DIMENSIONS);
      output.writeInt(Quantization.STRIDE);
      output.writeInt(clusterCount);
      output.writeInt(totalVectors);
      output.writeInt(config.ivfProbes);
      for (short s : quantCentroids) {
        output.writeShort(s);
      }
      for (long bucketOffset : bucketOffsets) {
        output.writeLong(bucketOffset);
      }
    }

    for (int i = 0; i < clusterCount; i++) {
      Files.deleteIfExists(vectorBucketFiles[i]);
      Files.deleteIfExists(labelBucketFiles[i]);
    }
    Files.deleteIfExists(tempDir);
  }

  /**
   * Encontra o centroide mais próximo que ainda tem espaço (bucket size < cap).
   * Se todos os centroides estiverem cheios, retorna o mais próximo absoluto
   * (estoura o cap em vez de descartar o vetor).
   *
   * orderScratch[0] no retorno é o nearestCentroid absoluto (independente de cap),
   * útil para contar quantos vetores foram redirecionados.
   *
   * Implementação: calcula todas as distâncias, ordena indices por distância
   * crescente (insertion sort em arrays primitivos — barato para 256 itens),
   * percorre na ordem até encontrar um com espaço.
   */
  private static int nearestAvailableCentroid(
      float[] vector, float[] centroids, int clusterCount,
      long[] bucketSizes, int maxBucketSize,
      float[] distScratch, int[] orderScratch) {
    for (int centroidId = 0; centroidId < clusterCount; centroidId++) {
      int base = centroidId * Vectorizer.PADDED_DIMENSIONS;
      distScratch[centroidId] = DistanceKernel.squared(centroids, base, vector);
      orderScratch[centroidId] = centroidId;
    }
    // Insertion sort indireto sobre orderScratch[] pela ordem de distScratch[].
    // 256 elementos: O(n²) = 65k comparações; insignificante perto do trabalho
    // de calcular as distâncias.
    for (int i = 1; i < clusterCount; i++) {
      int idx = orderScratch[i];
      float d = distScratch[idx];
      int j = i;
      while (j > 0 && distScratch[orderScratch[j - 1]] > d) {
        orderScratch[j] = orderScratch[j - 1];
        j--;
      }
      orderScratch[j] = idx;
    }
    // Primeiro com espaço.
    for (int i = 0; i < clusterCount; i++) {
      int candidate = orderScratch[i];
      if (bucketSizes[candidate] < maxBucketSize) {
        return candidate;
      }
    }
    // Todos cheios: retorna o mais próximo (estoura o cap).
    return orderScratch[0];
  }

  private static int nearestCentroid(float[] vector, float[] centroids, int clusterCount) {
    int bestId = 0;
    float bestDistance = Float.POSITIVE_INFINITY;
    for (int centroidId = 0; centroidId < clusterCount; centroidId++) {
      int base = centroidId * Vectorizer.PADDED_DIMENSIONS;
      float distance = DistanceKernel.squared(centroids, base, vector);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestId = centroidId;
      }
    }
    return bestId;
  }

  private static final class ReferenceReader implements AutoCloseable {
    private final PushbackInputStream input;
    private final byte[] vectorScratch = new byte[64];
    private final float[] vector = new float[Vectorizer.PADDED_DIMENSIONS];
    private byte label;
    private boolean started;
    private boolean ended;

    private ReferenceReader(Path referencesFile) throws IOException {
      this.input = new PushbackInputStream(
          new BufferedInputStream(
              new GZIPInputStream(Files.newInputStream(referencesFile))),
          8);
    }

    boolean next() throws IOException {
      if (ended)
        return false;
      if (!started) {
        consumeUntil('[');
        started = true;
      }
      int token = nextNonWhitespace();
      if (token == ']')
        return false;
      if (token != '{')
        throw new IOException("Objeto de referencia esperado");

      Arrays.fill(vector, 0.0f);
      label = 0;
      while (true) {
        String key = readString();
        consumeUntil(':');
        if ("vector".equals(key)) {
          readVector();
        } else if ("label".equals(key)) {
          label = (byte) ("fraud".equals(readString()) ? 1 : 0);
        } else {
          skipValue();
        }
        int separator = nextNonWhitespace();
        if (separator == ',')
          continue;
        if (separator == '}')
          break;
        throw new IOException("Separador invalido em referencia");
      }
      int trailer = nextNonWhitespace();
      if (trailer != ',' && trailer != ']')
        throw new IOException("Trailer invalido");
      if (trailer == ']')
        ended = true;
      return true;
    }

    float[] vector() {
      return vector;
    }

    byte label() {
      return label;
    }

    private void readVector() throws IOException {
      consumeUntil('[');
      for (int i = 0; i < Vectorizer.DIMENSIONS; i++) {
        vector[i] = readFloat();
        int separator = nextNonWhitespace();
        if (i < Vectorizer.DIMENSIONS - 1 && separator != ',')
          throw new IOException("Separador invalido no vetor");
        if (i == Vectorizer.DIMENSIONS - 1 && separator != ']')
          throw new IOException("Fechamento invalido no vetor");
      }
    }

    private float readFloat() throws IOException {
      int count = 0;
      int c = nextNonWhitespace();
      if (c == -1)
        throw new IOException("Fim inesperado ao ler float");
      vectorScratch[count++] = (byte) c;
      while ((c = input.read()) != -1) {
        if (c == ',' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
          if (c == ',' || c == ']')
            input.unread(c);
          break;
        }
        vectorScratch[count++] = (byte) c;
      }
      return Float.parseFloat(new String(vectorScratch, 0, count, StandardCharsets.US_ASCII));
    }

    private String readString() throws IOException {
      int quote = nextNonWhitespace();
      if (quote != '"')
        throw new IOException("String esperada");
      int count = 0;
      int c;
      while ((c = input.read()) != -1 && c != '"') {
        vectorScratch[count++] = (byte) c;
      }
      return new String(vectorScratch, 0, count, StandardCharsets.UTF_8);
    }

    private void skipValue() throws IOException {
      int c = nextNonWhitespace();
      if (c == '"') {
        while ((c = input.read()) != -1 && c != '"') {
        }
        return;
      }
      if (c == '[') {
        int depth = 1;
        while (depth > 0 && (c = input.read()) != -1) {
          if (c == '[')
            depth++;
          else if (c == ']')
            depth--;
        }
        return;
      }
      if (c == '{') {
        int depth = 1;
        while (depth > 0 && (c = input.read()) != -1) {
          if (c == '{')
            depth++;
          else if (c == '}')
            depth--;
        }
        return;
      }
      while (c != -1 && c != ',' && c != '}' && c != ']') {
        c = input.read();
      }
      if (c == ',' || c == '}' || c == ']')
        input.unread(c);
    }

    private void consumeUntil(char expected) throws IOException {
      int c;
      while ((c = input.read()) != -1) {
        if (c == expected)
          return;
      }
      throw new IOException("Caractere esperado nao encontrado: " + expected);
    }

    private int nextNonWhitespace() throws IOException {
      int c;
      while ((c = input.read()) != -1) {
        if (!Character.isWhitespace(c))
          return c;
      }
      return -1;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }
}
