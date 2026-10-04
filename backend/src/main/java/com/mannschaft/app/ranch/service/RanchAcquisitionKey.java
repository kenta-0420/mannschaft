package com.mannschaft.app.ranch.service;

import java.nio.charset.StandardCharsets;

/** 元identityを厳格ASCIIでVARBINARYへ写し、照合時に文字照合規則へ依存しない。 */
public final class RanchAcquisitionKey {
    private RanchAcquisitionKey() { }

    public static byte[] shopSku(String sku) {
        return ascii(sku, 160);
    }

    public static byte[] legacyBadge(String idType, String badgeId, String awardPeriod) {
        if (!"LONG".equals(idType) && !"UUID".equals(idType)) {
            throw new IllegalArgumentException("legacy badge ID型が不正です");
        }
        if (badgeId == null || badgeId.isBlank() || awardPeriod == null) {
            throw new IllegalArgumentException("legacy badge identityが不正です");
        }
        if ("LONG".equals(idType) && !badgeId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("legacy badge IDが正準decimalではありません");
        }
        if ("UUID".equals(idType)) {
            try {
                if (!java.util.UUID.fromString(badgeId).toString().equals(badgeId)) {
                    throw new IllegalArgumentException("legacy badge UUIDが正準値ではありません");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("legacy badge UUIDが不正です", exception);
            }
        }
        if (awardPeriod.contains("|")) {
            throw new IllegalArgumentException("legacy badge periodが不正です");
        }
        return ascii(idType + ":" + badgeId + "|" + awardPeriod, 160);
    }

    private static byte[] ascii(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.length() > maxBytes
                || !value.matches("[\\x20-\\x7E]+")) {
            throw new IllegalArgumentException("acquisition identityが不正です");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
