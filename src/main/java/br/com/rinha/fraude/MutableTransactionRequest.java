package br.com.rinha.fraude;

final class MutableTransactionRequest {
    static final int MAX_KNOWN_MERCHANTS = 32;

    byte[] payload;
    double transactionAmount;
    int transactionInstallments;
    long requestedAtEpochSecond;
    int requestedAtHour;
    int requestedAtDayOfWeek;
    double customerAvgAmount;
    int customerTxCount24h;
    final int[] knownMerchantOffsets = new int[MAX_KNOWN_MERCHANTS];
    final int[] knownMerchantLengths = new int[MAX_KNOWN_MERCHANTS];
    int knownMerchantCount;
    int merchantIdOffset;
    int merchantIdLength;
    int merchantMcc;
    boolean merchantMccValid;
    double merchantAvgAmount;
    boolean terminalOnline;
    boolean terminalCardPresent;
    double terminalKmFromHome;
    boolean hasLastTransaction;
    long lastTransactionEpochSecond;
    double lastTransactionKmFromCurrent;

    void reset() {
        transactionAmount = 0.0d;
        transactionInstallments = 0;
        requestedAtEpochSecond = 0L;
        requestedAtHour = 0;
        requestedAtDayOfWeek = 0;
        customerAvgAmount = 0.0d;
        customerTxCount24h = 0;
        knownMerchantCount = 0;
        merchantIdOffset = 0;
        merchantIdLength = 0;
        merchantMcc = 0;
        merchantMccValid = false;
        merchantAvgAmount = 0.0d;
        terminalOnline = false;
        terminalCardPresent = false;
        terminalKmFromHome = 0.0d;
        hasLastTransaction = false;
        lastTransactionEpochSecond = 0L;
        lastTransactionKmFromCurrent = 0.0d;
    }

    void addKnownMerchant(int offset, int length) {
        if (knownMerchantCount < knownMerchantOffsets.length) {
            knownMerchantOffsets[knownMerchantCount] = offset;
            knownMerchantLengths[knownMerchantCount] = length;
            knownMerchantCount++;
        }
    }

    boolean isUnknownMerchant() {
        if (merchantIdLength == 0) {
            return true;
        }
        final byte[] data = payload;
        final int idOffset = merchantIdOffset;
        final int idLen = merchantIdLength;
        for (int i = 0; i < knownMerchantCount; i++) {
            if (knownMerchantLengths[i] != idLen) {
                continue;
            }
            int km = knownMerchantOffsets[i];
            boolean equal = true;
            for (int j = 0; j < idLen; j++) {
                if (data[idOffset + j] != data[km + j]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return false;
            }
        }
        return true;
    }
}
