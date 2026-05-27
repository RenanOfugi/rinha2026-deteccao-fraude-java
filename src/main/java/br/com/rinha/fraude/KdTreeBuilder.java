package br.com.rinha.fraude;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Constrói uma kd-tree estática sobre os vetores de referência (14-D, baixa
 * dimensão — onde árvores espaciais batem IVF/força bruta). KNN exato em
 * ~centenas de comparações por query, contra ~50k do IVF.
 *
 * Build offline (no docker build). Layout serializado (todos LITTLE_ENDIAN):
 *
 *   meta.bin (kd.meta.bin):
 *     MAGIC | VERSION | dimensions | stride | totalVectors | nodeCount | leafSize
 *     nós[nodeCount], cada nó (20 bytes):
 *        splitDim   : int32  (-1 se folha)
 *        splitValue : int32  (valor int16 do split, na dim splitDim)
 *        left       : int32  (índice do filho esquerdo, ou -1)
 *        right      : int32  (índice do filho direito, ou -1)
 *        leafStart  : int32  (folha: início do range de vetores; interno: -1)
 *     (leafCount derivado de start do próximo; guardamos start e count)
 *
 *   Para simplicidade e robustez, cada nó carrega também leafCount:
 *        nó = splitDim, splitValue, left, right, leafStart, leafCount (24 bytes)
 *
 *   vectors.bin: int16 × STRIDE por vetor, REORDENADO pela construção (folhas
 *                contíguas → leitura sequencial por folha).
 *   labels.bin:  1 byte por vetor, mesma ordem reordenada.
 */
final class KdTreeBuilder {
    static final int MAGIC = 0x4B445452; // "KDTR"
    static final int VERSION = 1;
    static final int LEAF_SIZE = 64;
    static final int NODE_BYTES = 24;

    private KdTreeBuilder() {
    }

    static void build(AppConfig config) throws IOException {
        Files.createDirectories(config.indexDir);

        // 1) Carrega todos os vetores quantizados + labels na memória.
        int capacity = 3_200_000;
        short[][] vecsTmp = new short[capacity][];
        byte[] labelsTmp = new byte[capacity];
        int n = 0;
        try (ReferenceReader reader = new ReferenceReader(config.referencesFile())) {
            short[] q = new short[Quantization.STRIDE];
            while (reader.next()) {
                Quantization.quantize(reader.vector(), q, 0);
                vecsTmp[n] = Arrays.copyOf(q, Quantization.STRIDE);
                labelsTmp[n] = reader.label();
                n++;
            }
        }
        final int total = n;
        System.out.println("KdTree: carregados " + total + " vetores");

        // Achata num único short[] (AoS) e um índice de permutação.
        short[] vecs = new short[total * Quantization.STRIDE];
        byte[] labels = new byte[total];
        for (int i = 0; i < total; i++) {
            System.arraycopy(vecsTmp[i], 0, vecs, i * Quantization.STRIDE, Quantization.STRIDE);
            labels[i] = labelsTmp[i];
        }
        vecsTmp = null;
        labelsTmp = null;

        int[] order = new int[total];
        for (int i = 0; i < total; i++) order[i] = i;

        // 2) Constrói a árvore particionando o array `order` in-place por mediana.
        NodeList nodes = new NodeList(2 * (total / LEAF_SIZE + 1) + 16);
        buildNode(nodes, vecs, order, 0, total);
        System.out.println("KdTree: " + nodes.size + " nós, leafSize=" + LEAF_SIZE);

        // 3) Reordena vetores e labels conforme `order` (folhas contíguas).
        writeReordered(config, vecs, labels, order, total);

        // 4) Serializa os nós no meta.
        writeMeta(config, total, nodes);

        System.out.println("KdTree: build concluído.");
    }

    /**
     * Constrói recursivamente. order[lo,hi) são os índices dos vetores deste
     * subárvore. Retorna o índice do nó criado em `nodes`.
     */
    private static int buildNode(NodeList nodes, short[] vecs, int[] order, int lo, int hi) {
        int count = hi - lo;
        int nodeIdx = nodes.alloc();

        if (count <= LEAF_SIZE) {
            nodes.setLeaf(nodeIdx, lo, count);
            return nodeIdx;
        }

        // Escolhe a dimensão de maior amplitude (spread) neste subconjunto.
        int splitDim = widestDimension(vecs, order, lo, hi);

        // Particiona pela mediana na splitDim (ordena order[lo,hi) por essa dim
        // só o suficiente para achar a mediana — usamos sort parcial simples).
        int mid = lo + count / 2;
        nthElement(vecs, order, lo, hi, mid, splitDim);
        int splitValue = vecs[order[mid] * Quantization.STRIDE + splitDim];

        // Garante que elementos == splitValue não causem partição degenerada:
        // tudo < mid vai pra esquerda, >= mid pra direita.
        int leftChild = buildNode(nodes, vecs, order, lo, mid);
        int rightChild = buildNode(nodes, vecs, order, mid, hi);

        nodes.setInternal(nodeIdx, splitDim, splitValue, leftChild, rightChild);
        return nodeIdx;
    }

    /** Dimensão com maior (max-min) entre os vetores de order[lo,hi). */
    private static int widestDimension(short[] vecs, int[] order, int lo, int hi) {
        int bestDim = 0;
        int bestSpread = -1;
        for (int d = 0; d < Quantization.DIM; d++) {
            short min = Short.MAX_VALUE;
            short max = Short.MIN_VALUE;
            for (int i = lo; i < hi; i++) {
                short v = vecs[order[i] * Quantization.STRIDE + d];
                if (v < min) min = v;
                if (v > max) max = v;
            }
            int spread = max - min;
            if (spread > bestSpread) {
                bestSpread = spread;
                bestDim = d;
            }
        }
        return bestDim;
    }

    /**
     * Rearranja order[lo,hi) parcialmente de modo que order[k] fique na posição
     * que teria se ordenado por vecs[..][dim], e todos antes de k sejam <= e
     * depois >= (quickselect / nth_element).
     */
    private static void nthElement(short[] vecs, int[] order, int lo, int hi, int k, int dim) {
        int left = lo;
        int right = hi - 1;
        while (left < right) {
            int pivot = vecs[order[left + (right - left) / 2] * Quantization.STRIDE + dim];
            int i = left;
            int j = right;
            while (i <= j) {
                while (vecs[order[i] * Quantization.STRIDE + dim] < pivot) i++;
                while (vecs[order[j] * Quantization.STRIDE + dim] > pivot) j--;
                if (i <= j) {
                    int t = order[i]; order[i] = order[j]; order[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) right = j;
            else if (k >= i) left = i;
            else break;
        }
    }

    private static void writeReordered(AppConfig config, short[] vecs, byte[] labels,
            int[] order, int total) throws IOException {
        try (OutputStream vOut = new BufferedOutputStream(Files.newOutputStream(config.vectorsFile(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
             OutputStream lOut = new BufferedOutputStream(Files.newOutputStream(config.labelsFile(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            ByteBuffer vb = ByteBuffer.allocate(Quantization.STRIDE * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < total; i++) {
                int src = order[i];
                vb.clear();
                int base = src * Quantization.STRIDE;
                for (int s = 0; s < Quantization.STRIDE; s++) {
                    vb.putShort(vecs[base + s]);
                }
                vOut.write(vb.array(), 0, vb.position());
                lOut.write(labels[src]);
            }
        }
    }

    private static void writeMeta(AppConfig config, int total, NodeList nodes) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(
                7 * Integer.BYTES + nodes.size * NODE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(VERSION);
        header.putInt(Vectorizer.DIMENSIONS);
        header.putInt(Quantization.STRIDE);
        header.putInt(total);
        header.putInt(nodes.size);
        header.putInt(LEAF_SIZE);
        for (int i = 0; i < nodes.size; i++) {
            header.putInt(nodes.splitDim[i]);
            header.putInt(nodes.splitValue[i]);
            header.putInt(nodes.left[i]);
            header.putInt(nodes.right[i]);
            header.putInt(nodes.leafStart[i]);
            header.putInt(nodes.leafCount[i]);
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(config.metadataFile(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.write(header.array(), 0, header.position());
        }
    }

    /** Array-of-arrays de nós, crescido sob demanda. */
    private static final class NodeList {
        int[] splitDim;
        int[] splitValue;
        int[] left;
        int[] right;
        int[] leafStart;
        int[] leafCount;
        int size;

        NodeList(int cap) {
            cap = Math.max(cap, 16);
            splitDim = new int[cap];
            splitValue = new int[cap];
            left = new int[cap];
            right = new int[cap];
            leafStart = new int[cap];
            leafCount = new int[cap];
        }

        int alloc() {
            if (size == splitDim.length) grow();
            return size++;
        }

        private void grow() {
            int nc = splitDim.length * 2;
            splitDim = Arrays.copyOf(splitDim, nc);
            splitValue = Arrays.copyOf(splitValue, nc);
            left = Arrays.copyOf(left, nc);
            right = Arrays.copyOf(right, nc);
            leafStart = Arrays.copyOf(leafStart, nc);
            leafCount = Arrays.copyOf(leafCount, nc);
        }

        void setLeaf(int i, int start, int count) {
            splitDim[i] = -1;
            splitValue[i] = 0;
            left[i] = -1;
            right[i] = -1;
            leafStart[i] = start;
            leafCount[i] = count;
        }

        void setInternal(int i, int dim, int value, int l, int r) {
            splitDim[i] = dim;
            splitValue[i] = value;
            left[i] = l;
            right[i] = r;
            leafStart[i] = -1;
            leafCount[i] = 0;
        }
    }

    // ----- Leitor de referências (mesma lógica do IvfIndexBuilder) -----
    private static final class ReferenceReader implements AutoCloseable {
        private final PushbackInputStream input;
        private final byte[] vectorScratch = new byte[64];
        private final float[] vector = new float[Vectorizer.PADDED_DIMENSIONS];
        private byte label;
        private boolean started;
        private boolean ended;

        private ReferenceReader(Path referencesFile) throws IOException {
            this.input = new PushbackInputStream(
                    new BufferedInputStream(new GZIPInputStream(Files.newInputStream(referencesFile))), 8);
        }

        boolean next() throws IOException {
            if (ended) return false;
            if (!started) {
                consumeUntil('[');
                started = true;
            }
            int token = nextNonWhitespace();
            if (token == ']') return false;
            if (token != '{') throw new IOException("Objeto de referencia esperado");
            Arrays.fill(vector, 0.0f);
            label = 0;
            while (true) {
                String key = readString();
                consumeUntil(':');
                if ("vector".equals(key)) readVector();
                else if ("label".equals(key)) label = (byte) ("fraud".equals(readString()) ? 1 : 0);
                else skipValue();
                int separator = nextNonWhitespace();
                if (separator == ',') continue;
                if (separator == '}') break;
                throw new IOException("Separador invalido em referencia");
            }
            int trailer = nextNonWhitespace();
            if (trailer != ',' && trailer != ']') throw new IOException("Trailer invalido");
            if (trailer == ']') ended = true;
            return true;
        }

        float[] vector() { return vector; }
        byte label() { return label; }

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
            if (c == -1) throw new IOException("Fim inesperado ao ler float");
            vectorScratch[count++] = (byte) c;
            while ((c = input.read()) != -1) {
                if (c == ',' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    if (c == ',' || c == ']') input.unread(c);
                    break;
                }
                vectorScratch[count++] = (byte) c;
            }
            return Float.parseFloat(new String(vectorScratch, 0, count, StandardCharsets.US_ASCII));
        }

        private String readString() throws IOException {
            int quote = nextNonWhitespace();
            if (quote != '"') throw new IOException("String esperada");
            int count = 0;
            int c;
            while ((c = input.read()) != -1 && c != '"') vectorScratch[count++] = (byte) c;
            return new String(vectorScratch, 0, count, StandardCharsets.UTF_8);
        }

        private void skipValue() throws IOException {
            int c = nextNonWhitespace();
            if (c == '"') { while ((c = input.read()) != -1 && c != '"') {} return; }
            if (c == '[') {
                int depth = 1;
                while (depth > 0 && (c = input.read()) != -1) {
                    if (c == '[') depth++; else if (c == ']') depth--;
                }
                return;
            }
            if (c == '{') {
                int depth = 1;
                while (depth > 0 && (c = input.read()) != -1) {
                    if (c == '{') depth++; else if (c == '}') depth--;
                }
                return;
            }
            while (c != -1 && c != ',' && c != '}' && c != ']') c = input.read();
            if (c == ',' || c == '}' || c == ']') input.unread(c);
        }

        private void consumeUntil(char expected) throws IOException {
            int c;
            while ((c = input.read()) != -1) if (c == expected) return;
            throw new IOException("Caractere esperado nao encontrado: " + expected);
        }

        private int nextNonWhitespace() throws IOException {
            int c;
            while ((c = input.read()) != -1) if (!Character.isWhitespace(c)) return c;
            return -1;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
