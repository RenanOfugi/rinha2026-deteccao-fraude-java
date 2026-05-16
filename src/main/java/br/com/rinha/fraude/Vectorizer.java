package br.com.rinha.fraude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class Vectorizer {
    static final int DIMENSIONS = 14;
    static final int PADDED_DIMENSIONS = 16;
    private static final float DEFAULT_MCC_RISK = 0.5f;

    private final float invMaxAmount;
    private final float invMaxInstallments;
    private final float invAmountVsAvgRatio;
    private final float invMaxMinutes;
    private final float invMaxKm;
    private final float invMaxTxCount24h;
    private final float invMaxMerchantAvgAmount;
    private final int[] mccKeys;
    private final float[] mccValues;
    private final int mccTableMask;

    private Vectorizer(
        float maxAmount,
        float maxInstallments,
        float amountVsAvgRatio,
        float maxMinutes,
        float maxKm,
        float maxTxCount24h,
        float maxMerchantAvgAmount,
        int[] mccKeys,
        float[] mccValues,
        int mccTableMask
    ) {
        this.invMaxAmount = 1.0f / maxAmount;
        this.invMaxInstallments = 1.0f / maxInstallments;
        this.invAmountVsAvgRatio = 1.0f / amountVsAvgRatio;
        this.invMaxMinutes = 1.0f / maxMinutes;
        this.invMaxKm = 1.0f / maxKm;
        this.invMaxTxCount24h = 1.0f / maxTxCount24h;
        this.invMaxMerchantAvgAmount = 1.0f / maxMerchantAvgAmount;
        this.mccKeys = mccKeys;
        this.mccValues = mccValues;
        this.mccTableMask = mccTableMask;
    }

    static Vectorizer load(Path normalizationFile, Path mccRiskFile) throws IOException {
        Map<String, Double> normalization = parseNumberMap(Files.readString(normalizationFile, StandardCharsets.UTF_8));
        Map<String, Double> riskSource = parseNumberMap(Files.readString(mccRiskFile, StandardCharsets.UTF_8));

        int size = Math.max(16, Integer.highestOneBit(riskSource.size() * 4 - 1) << 1);
        int mask = size - 1;
        int[] keys = new int[size];
        float[] values = new float[size];
        java.util.Arrays.fill(keys, -1);
        for (Map.Entry<String, Double> entry : riskSource.entrySet()) {
            int mcc = Integer.parseInt(entry.getKey());
            int idx = mcc & mask;
            while (keys[idx] != -1) {
                idx = (idx + 1) & mask;
            }
            keys[idx] = mcc;
            values[idx] = entry.getValue().floatValue();
        }

        return new Vectorizer(
            normalization.get("max_amount").floatValue(),
            normalization.get("max_installments").floatValue(),
            normalization.get("amount_vs_avg_ratio").floatValue(),
            normalization.get("max_minutes").floatValue(),
            normalization.get("max_km").floatValue(),
            normalization.get("max_tx_count_24h").floatValue(),
            normalization.get("max_merchant_avg_amount").floatValue(),
            keys, values, mask
        );
    }

    void fillQueryVector(MutableTransactionRequest request, float[] output) {
        output[0]  = clamp((float) request.transactionAmount * invMaxAmount);
        output[1]  = clamp((float) request.transactionInstallments * invMaxInstallments);
        output[2]  = clamp((float) amountVsAverage(request.transactionAmount, request.customerAvgAmount));
        output[3]  = request.requestedAtHour * (1.0f / 23.0f);
        output[4]  = request.requestedAtDayOfWeek * (1.0f / 6.0f);
        if (request.hasLastTransaction) {
            long minutes = (request.requestedAtEpochSecond - request.lastTransactionEpochSecond) / 60L;
            if (minutes < 0L) minutes = 0L;
            output[5] = clamp(minutes * invMaxMinutes);
            output[6] = clamp((float) request.lastTransactionKmFromCurrent * invMaxKm);
        } else {
            output[5] = -1.0f;
            output[6] = -1.0f;
        }
        output[7]  = clamp((float) request.terminalKmFromHome * invMaxKm);
        output[8]  = clamp((float) request.customerTxCount24h * invMaxTxCount24h);
        output[9]  = request.terminalOnline ? 1.0f : 0.0f;
        output[10] = request.terminalCardPresent ? 1.0f : 0.0f;
        output[11] = request.isUnknownMerchant() ? 1.0f : 0.0f;
        output[12] = lookupMccRisk(request.merchantMcc, request.merchantMccValid);
        output[13] = clamp((float) request.merchantAvgAmount * invMaxMerchantAvgAmount);
        output[14] = 0.0f;
        output[15] = 0.0f;
    }

    private float lookupMccRisk(int mcc, boolean valid) {
        if (!valid) {
            return DEFAULT_MCC_RISK;
        }
        int mask = mccTableMask;
        int idx = mcc & mask;
        int[] keys = mccKeys;
        while (true) {
            int key = keys[idx];
            if (key == mcc) {
                return mccValues[idx];
            }
            if (key == -1) {
                return DEFAULT_MCC_RISK;
            }
            idx = (idx + 1) & mask;
        }
    }

    private double amountVsAverage(double amount, double avgAmount) {
        if (avgAmount <= 0.0d) {
            return amount > 0.0d ? 1.0d : 0.0d;
        }
        return (amount / avgAmount) * invAmountVsAvgRatio;
    }

    private static float clamp(float value) {
        if (value < 0.0f) return 0.0f;
        return value > 1.0f ? 1.0f : value;
    }

    private static Map<String, Double> parseNumberMap(String json) {
        Map<String, Double> result = new HashMap<>();
        int index = 0;
        while (index < json.length()) {
            int start = json.indexOf('"', index);
            if (start < 0) {
                break;
            }
            int end = json.indexOf('"', start + 1);
            String key = json.substring(start + 1, end);
            int colon = json.indexOf(':', end);
            int valueStart = colon + 1;
            while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
                valueStart++;
            }
            int valueEnd = valueStart;
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if ((c < '0' || c > '9') && c != '.' && c != '-') {
                    break;
                }
                valueEnd++;
            }
            result.put(key, Double.parseDouble(json.substring(valueStart, valueEnd)));
            index = valueEnd;
        }
        return result;
    }
}
