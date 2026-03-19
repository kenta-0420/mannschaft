package com.mannschaft.app.membership.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * QRトークンの生成・検証を担当するサービス。HMAC-SHA256署名付きトークンを扱う。
 */
@Service
public class QrTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int QR_TOKEN_EXPIRY_SECONDS = 300;
    private static final int SECRET_LENGTH = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 会員証QRトークンを生成する。
     * 形式: {card_code}.{issued_at_epoch}.{expires_at_epoch}.{signature}
     *
     * @param cardCode 会員証コード（UUID）
     * @param qrSecret QR署名用シークレット
     * @return 署名付きQRトークン
     */
    public String generateMemberCardQrToken(String cardCode, String qrSecret) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + QR_TOKEN_EXPIRY_SECONDS;

        String payload = cardCode + "." + issuedAt + "." + expiresAt;
        String signature = sign(payload, qrSecret);
        return payload + "." + signature;
    }

    /**
     * 会員証QRトークンのパース結果。
     */
    public record MemberCardTokenPayload(String cardCode, long issuedAt, long expiresAt, String signature) {}

    /**
     * 会員証QRトークンをパースする。
     *
     * @param qrToken QRトークン
     * @return パース結果。フォーマット不正の場合はnull
     */
    public MemberCardTokenPayload parseMemberCardToken(String qrToken) {
        if (qrToken == null || qrToken.isBlank()) {
            return null;
        }
        String[] parts = qrToken.split("\\.", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            String cardCode = parts[0];
            long issuedAt = Long.parseLong(parts[1]);
            long expiresAt = Long.parseLong(parts[2]);
            String signature = parts[3];
            return new MemberCardTokenPayload(cardCode, issuedAt, expiresAt, signature);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 会員証QRトークンの署名を検証する。
     *
     * @param payload パースされたペイロード
     * @param qrSecret QR署名用シークレット
     * @return 署名が有効であればtrue
     */
    public boolean verifyMemberCardSignature(MemberCardTokenPayload payload, String qrSecret) {
        String data = payload.cardCode() + "." + payload.issuedAt() + "." + payload.expiresAt();
        String expectedSignature = sign(data, qrSecret);
        return constantTimeEquals(expectedSignature, payload.signature());
    }

    /**
     * 会員証QRトークンの有効期限を検証する。
     *
     * @param payload パースされたペイロード
     * @return 有効期限内であればtrue
     */
    public boolean isTokenExpired(MemberCardTokenPayload payload) {
        return Instant.now().getEpochSecond() > payload.expiresAt();
    }

    /**
     * 拠点QRトークンを生成する。
     * 形式: {location_code}.{signature}
     *
     * @param locationCode 拠点コード（UUID）
     * @param locationSecret 拠点署名用シークレット
     * @return 署名付き拠点QRトークン
     */
    public String generateLocationQrToken(String locationCode, String locationSecret) {
        String signature = sign(locationCode, locationSecret);
        return locationCode + "." + signature;
    }

    /**
     * 拠点QRトークンのパース結果。
     */
    public record LocationTokenPayload(String locationCode, String signature) {}

    /**
     * 拠点QRトークンをパースする。
     *
     * @param token 拠点QRトークン
     * @return パース結果。フォーマット不正の場合はnull
     */
    public LocationTokenPayload parseLocationToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // UUIDは36文字 + "." + signature
        int lastDot = token.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= token.length() - 1) {
            return null;
        }
        String locationCode = token.substring(0, lastDot);
        String signature = token.substring(lastDot + 1);
        return new LocationTokenPayload(locationCode, signature);
    }

    /**
     * 拠点QRトークンの署名を検証する。
     *
     * @param payload パースされたペイロード
     * @param locationSecret 拠点署名用シークレット
     * @return 署名が有効であればtrue
     */
    public boolean verifyLocationSignature(LocationTokenPayload payload, String locationSecret) {
        String expectedSignature = sign(payload.locationCode(), locationSecret);
        return constantTimeEquals(expectedSignature, payload.signature());
    }

    /**
     * ランダムシークレットを生成する。
     *
     * @return 64文字のランダム文字列
     */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_LENGTH / 2];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(SECRET_LENGTH);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * QRトークンの有効期限（秒）を返す。
     */
    public int getExpirySeconds() {
        return QR_TOKEN_EXPIRY_SECONDS;
    }

    /**
     * HMAC-SHA256で署名する。
     */
    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256署名の生成に失敗しました", e);
        }
    }

    /**
     * タイミング攻撃対策の定数時間比較。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
