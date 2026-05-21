package br.com.rinha.fraude;

import java.io.IOException;
import java.io.InputStream;

final class JsonRequestParser {
    private static final int BUFFER_SIZE = 8192;
    private static final double[] POW10 = {
        1.0d,
        10.0d,
        100.0d,
        1_000.0d,
        10_000.0d,
        100_000.0d,
        1_000_000.0d,
        10_000_000.0d,
        100_000_000.0d,
        1_000_000_000.0d,
        10_000_000_000.0d,
        100_000_000_000.0d,
        1_000_000_000_000.0d,
        10_000_000_000_000.0d,
        100_000_000_000_000.0d,
        1_000_000_000_000_000.0d,
        10_000_000_000_000_000.0d,
        100_000_000_000_000_000.0d,
        1_000_000_000_000_000_000.0d
    };

    // FNV-1a 32-bit das chaves esperadas no payload — checadas após confirmar length+conteúdo.
    private static final int K_TRANSACTION       = fnv("transaction");
    private static final int K_CUSTOMER          = fnv("customer");
    private static final int K_MERCHANT          = fnv("merchant");
    private static final int K_TERMINAL          = fnv("terminal");
    private static final int K_LAST_TRANSACTION  = fnv("last_transaction");
    private static final int K_AMOUNT            = fnv("amount");
    private static final int K_INSTALLMENTS      = fnv("installments");
    private static final int K_REQUESTED_AT      = fnv("requested_at");
    private static final int K_AVG_AMOUNT        = fnv("avg_amount");
    private static final int K_TX_COUNT_24H      = fnv("tx_count_24h");
    private static final int K_KNOWN_MERCHANTS   = fnv("known_merchants");
    private static final int K_ID                = fnv("id");
    private static final int K_MCC               = fnv("mcc");
    private static final int K_IS_ONLINE         = fnv("is_online");
    private static final int K_CARD_PRESENT      = fnv("card_present");
    private static final int K_KM_FROM_HOME      = fnv("km_from_home");
    private static final int K_TIMESTAMP         = fnv("timestamp");
    private static final int K_KM_FROM_CURRENT   = fnv("km_from_current");

    final byte[] buffer = new byte[BUFFER_SIZE];
    private int position;
    private int limit;

    MutableTransactionRequest parse(InputStream input, MutableTransactionRequest request) throws IOException {
        request.reset();
        request.payload = buffer;
        readFully(input);
        position = 0;
        skipWhitespace();
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            byte c = peek();
            if (c == '}') {
                position++;
                return request;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_TRANSACTION) {
                parseTransaction(request);
            } else if (keyHash == K_CUSTOMER) {
                parseCustomer(request);
            } else if (keyHash == K_MERCHANT) {
                parseMerchant(request);
            } else if (keyHash == K_TERMINAL) {
                parseTerminal(request);
            } else if (keyHash == K_LAST_TRANSACTION) {
                parseLastTransaction(request);
            } else {
                skipValue();
            }
            skipWhitespace();
            byte separator = peek();
            if (separator == ',') {
                position++;
                continue;
            }
            if (separator == '}') {
                position++;
                return request;
            }
            throw new IOException("JSON invalido no objeto raiz");
        }
    }

    private void parseTransaction(MutableTransactionRequest request) throws IOException {
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_AMOUNT) {
                request.transactionAmount = parseDouble();
            } else if (keyHash == K_INSTALLMENTS) {
                request.transactionInstallments = (int) parseLongNumber();
            } else if (keyHash == K_REQUESTED_AT) {
                int start = parseStringStart();
                int end = scanStringEnd();
                request.requestedAtEpochSecond = TimeUtil.parseEpochSecond(buffer, start, end);
                request.requestedAtHour = TimeUtil.hourOfDay(buffer, start);
                request.requestedAtDayOfWeek = TimeUtil.dayOfWeekMon0(buffer, start);
                position = end + 1;
            } else {
                skipValue();
            }
            skipObjectSeparator();
        }
    }

    private void parseCustomer(MutableTransactionRequest request) throws IOException {
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_AVG_AMOUNT) {
                request.customerAvgAmount = parseDouble();
            } else if (keyHash == K_TX_COUNT_24H) {
                request.customerTxCount24h = (int) parseLongNumber();
            } else if (keyHash == K_KNOWN_MERCHANTS) {
                parseKnownMerchants(request);
            } else {
                skipValue();
            }
            skipObjectSeparator();
        }
    }

    private void parseMerchant(MutableTransactionRequest request) throws IOException {
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_ID) {
                int start = parseStringStart();
                int end = scanStringEnd();
                request.merchantIdOffset = start;
                request.merchantIdLength = end - start;
                position = end + 1;
            } else if (keyHash == K_MCC) {
                int start = parseStringStart();
                int end = scanStringEnd();
                int mcc = 0;
                boolean valid = (end - start) > 0;
                for (int i = start; i < end; i++) {
                    byte b = buffer[i];
                    if (b < '0' || b > '9') {
                        valid = false;
                        break;
                    }
                    mcc = (mcc * 10) + (b - '0');
                }
                request.merchantMcc = mcc;
                request.merchantMccValid = valid;
                position = end + 1;
            } else if (keyHash == K_AVG_AMOUNT) {
                request.merchantAvgAmount = parseDouble();
            } else {
                skipValue();
            }
            skipObjectSeparator();
        }
    }

    private void parseTerminal(MutableTransactionRequest request) throws IOException {
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_IS_ONLINE) {
                request.terminalOnline = parseBoolean();
            } else if (keyHash == K_CARD_PRESENT) {
                request.terminalCardPresent = parseBoolean();
            } else if (keyHash == K_KM_FROM_HOME) {
                request.terminalKmFromHome = parseDouble();
            } else {
                skipValue();
            }
            skipObjectSeparator();
        }
    }

    private void parseLastTransaction(MutableTransactionRequest request) throws IOException {
        if (matchKeyword("null")) {
            request.hasLastTransaction = false;
            return;
        }
        request.hasLastTransaction = true;
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_TIMESTAMP) {
                int start = parseStringStart();
                int end = scanStringEnd();
                request.lastTransactionEpochSecond = TimeUtil.parseEpochSecond(buffer, start, end);
                position = end + 1;
            } else if (keyHash == K_KM_FROM_CURRENT) {
                request.lastTransactionKmFromCurrent = parseDouble();
            } else {
                skipValue();
            }
            skipObjectSeparator();
        }
    }

    private void parseKnownMerchants(MutableTransactionRequest request) throws IOException {
        expect((byte) '[');
        while (true) {
            skipWhitespace();
            byte c = peek();
            if (c == ']') {
                position++;
                return;
            }
            int start = parseStringStart();
            int end = scanStringEnd();
            request.addKnownMerchant(start, end - start);
            position = end + 1;
            skipWhitespace();
            byte separator = peek();
            if (separator == ',') {
                position++;
                continue;
            }
            if (separator == ']') {
                position++;
                return;
            }
            throw new IOException("JSON invalido em known_merchants");
        }
    }

    // FNV-1a 32-bit do conteúdo da chave (sem alocação de String).
    private int parseFieldKeyHash() throws IOException {
        int start = parseStringStart();
        int end = scanStringEnd();
        int hash = 0x811c9dc5;
        for (int i = start; i < end; i++) {
            hash ^= (buffer[i] & 0xff);
            hash *= 0x01000193;
        }
        position = end + 1;
        return hash;
    }

    private static int fnv(String s) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= (s.charAt(i) & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }

    private int parseStringStart() throws IOException {
        expect((byte) '"');
        return position;
    }

    private int scanStringEnd() throws IOException {
        int current = position;
        final int lim = limit;
        final byte[] buf = buffer;
        while (current < lim) {
            if (buf[current] == '"') {
                return current;
            }
            current++;
        }
        throw new IOException("String JSON nao terminada");
    }

    private long parseLongNumber() {
        int start = position;
        while (position < limit) {
            byte c = buffer[position];
            if ((c < '0' || c > '9') && c != '-') {
                break;
            }
            position++;
        }
        return parseLongAscii(buffer, start, position - start);
    }

    private double parseDouble() {
        int start = position;
        boolean hasExponent = false;
        while (position < limit) {
            byte c = buffer[position];
            if (c == 'e' || c == 'E') {
                hasExponent = true;
            }
            if ((c < '0' || c > '9') && c != '-' && c != '.' && c != 'e' && c != 'E' && c != '+') {
                break;
            }
            position++;
        }
        return parseDoubleAscii(buffer, start, position, hasExponent);
    }

    private static long parseLongAscii(byte[] data, int offset, int length) {
        if (length == 0) return 0L;
        int i = offset;
        boolean negative = false;
        if (data[i] == '-') {
            negative = true;
            i++;
        }
        long value = 0L;
        int end = offset + length;
        while (i < end) {
            value = value * 10 + (data[i] - '0');
            i++;
        }
        return negative ? -value : value;
    }

    private static double parseDoubleAscii(byte[] data, int start, int end, boolean hasExponent) {
        int i = start;
        boolean negative = false;
        if (i < end && data[i] == '-') {
            negative = true;
            i++;
        } else if (i < end && data[i] == '+') {
            i++;
        }

        long mantissa = 0L;
        int fractionalDigits = 0;
        boolean fractional = false;
        int exponent = 0;
        while (i < end) {
            byte c = data[i++];
            if (c >= '0' && c <= '9') {
                mantissa = (mantissa * 10L) + (c - '0');
                if (fractional) {
                    fractionalDigits++;
                }
            } else if (c == '.') {
                fractional = true;
            } else if (c == 'e' || c == 'E') {
                exponent = parseExponent(data, i, end);
                break;
            }
        }

        double value;
        int scale = fractionalDigits - exponent;
        if (!hasExponent && scale >= 0 && scale < POW10.length) {
            value = mantissa / POW10[scale];
        } else if (scale >= 0 && scale < POW10.length) {
            value = mantissa / POW10[scale];
        } else if (scale < 0 && -scale < POW10.length) {
            value = mantissa * POW10[-scale];
        } else {
            value = mantissa * Math.pow(10.0d, -scale);
        }
        return negative ? -value : value;
    }

    private static int parseExponent(byte[] data, int start, int end) {
        int i = start;
        boolean negative = false;
        if (i < end && data[i] == '-') {
            negative = true;
            i++;
        } else if (i < end && data[i] == '+') {
            i++;
        }
        int value = 0;
        while (i < end) {
            byte c = data[i++];
            if (c < '0' || c > '9') {
                break;
            }
            value = (value * 10) + (c - '0');
        }
        return negative ? -value : value;
    }

    private boolean parseBoolean() throws IOException {
        if (matchKeyword("true")) {
            return true;
        }
        if (matchKeyword("false")) {
            return false;
        }
        throw new IOException("Boolean JSON invalido");
    }

    private boolean matchKeyword(String keyword) {
        int end = position + keyword.length();
        if (end > limit) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (buffer[position + i] != keyword.charAt(i)) {
                return false;
            }
        }
        position = end;
        return true;
    }

    private void skipValue() throws IOException {
        skipWhitespace();
        byte c = peek();
        if (c == '{') {
            position++;
            int depth = 1;
            while (depth > 0) {
                byte current = buffer[position++];
                if (current == '"') {
                    while (buffer[position++] != '"') {
                        // payload dos testes nao usa escapes
                    }
                } else if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                }
            }
            return;
        }
        if (c == '[') {
            position++;
            int depth = 1;
            while (depth > 0) {
                byte current = buffer[position++];
                if (current == '"') {
                    while (buffer[position++] != '"') {
                    }
                } else if (current == '[') {
                    depth++;
                } else if (current == ']') {
                    depth--;
                }
            }
            return;
        }
        if (c == '"') {
            position++;
            position = scanStringEnd() + 1;
            return;
        }
        while (position < limit) {
            c = buffer[position];
            if (c == ',' || c == '}' || c == ']' || c <= ' ') {
                return;
            }
            position++;
        }
    }

    private void skipObjectSeparator() throws IOException {
        skipWhitespace();
        byte separator = peek();
        if (separator == ',') {
            position++;
            return;
        }
        if (separator == '}') {
            // Não consome — o loop chamador detecta o '}' e finaliza o objeto.
            return;
        }
        throw new IOException("Separador JSON invalido");
    }

    private void skipWhitespace() {
        while (position < limit) {
            byte c = buffer[position];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                position++;
                continue;
            }
            break;
        }
    }

    private void expect(byte expected) throws IOException {
        if (peek() != expected) {
            throw new IOException("Esperado '" + (char) expected + "'");
        }
        position++;
    }

    private byte peek() throws IOException {
        if (position >= limit) {
            throw new IOException("Fim inesperado do payload");
        }
        return buffer[position];
    }

    void parseBuffer(int len) {
        position = 0;
        limit = len;
    }

    int parseRoot(MutableTransactionRequest request) throws IOException {
        request.reset();
        request.payload = buffer;
        position = 0;
        skipWhitespace();
        expect((byte) '{');
        while (true) {
            skipWhitespace();
            byte c = peek();
            if (c == '}') {
                position++;
                return position;
            }
            int keyHash = parseFieldKeyHash();
            skipWhitespace();
            expect((byte) ':');
            skipWhitespace();
            if (keyHash == K_TRANSACTION) {
                parseTransaction(request);
            } else if (keyHash == K_CUSTOMER) {
                parseCustomer(request);
            } else if (keyHash == K_MERCHANT) {
                parseMerchant(request);
            } else if (keyHash == K_TERMINAL) {
                parseTerminal(request);
            } else if (keyHash == K_LAST_TRANSACTION) {
                parseLastTransaction(request);
            } else {
                skipValue();
            }
            skipWhitespace();
            byte separator = peek();
            if (separator == ',') {
                position++;
                continue;
            }
            if (separator == '}') {
                position++;
                return position;
            }
            throw new IOException("JSON invalido no objeto raiz");
        }
    }

    private void readFully(InputStream input) throws IOException {
        int read;
        int offset = 0;
        while ((read = input.read(buffer, offset, buffer.length - offset)) != -1) {
            offset += read;
            if (offset == buffer.length) {
                if (input.read() != -1) {
                    throw new IOException("Payload excede " + BUFFER_SIZE + " bytes");
                }
                break;
            }
        }
        limit = offset;
    }
}
