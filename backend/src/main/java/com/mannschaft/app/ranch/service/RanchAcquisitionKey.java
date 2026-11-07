package com.mannschaft.app.ranch.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 元identityをASCII正準byte列へ一意に写し、DB文字照合規則へ依存しない。 */
public final class RanchAcquisitionKey {
    private RanchAcquisitionKey() { }

    public static byte[] shopSku(String sku) {
        return ascii(sku, 160);
    }

    public static byte[] legacyBadge(String idType, String badgeId, String awardPeriod) {
        if (!"LONG".equals(idType) && !"UUID".equals(idType)) {
            throw new IllegalArgumentException("legacy badge ID型が不正です");
        }
        if (badgeId == null || badgeId.isBlank()) {
            throw new IllegalArgumentException("legacy badge identityが不正です");
        }
        String period = awardPeriod == null ? "" : awardPeriod;
        if (period.codePointCount(0, period.length()) > 20
                || !StandardCharsets.UTF_8.newEncoder().canEncode(period)) {
            throw new IllegalArgumentException("legacy badge periodが不正です");
        }
        if ("LONG".equals(idType)) {
            try {
                if (!badgeId.matches("[1-9][0-9]*") || Long.parseLong(badgeId) <= 0) {
                    throw new IllegalArgumentException("legacy badge IDが正準decimalではありません");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("legacy badge IDが不正です", exception);
            }
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
        byte[] periodBytes = period.getBytes(StandardCharsets.UTF_8);
        String encodedPeriod = Base64.getUrlEncoder().withoutPadding().encodeToString(periodBytes);
        // 旧VARCHAR(20)の最大UTF-8 80byteもBase64url 107文字に収まり、
        // UUID36文字を含む全体でも160byte未満。全periodを同じ方式で符号化する。
        return ascii("LB1:" + idType + ":" + badgeId + ":" + encodedPeriod, 160);
    }

    private static byte[] ascii(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.length() > maxBytes
                || !value.matches("[\\x20-\\x7E]+")) {
            throw new IllegalArgumentException("acquisition identityが不正です");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
