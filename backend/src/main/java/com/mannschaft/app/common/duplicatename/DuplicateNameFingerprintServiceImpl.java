package com.mannschaft.app.common.duplicatename;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameFingerprintService} の実装。
 *
 * <p>金型: {@code com.mannschaft.app.membership.service.QrTokenService}（HmacSHA256, TTL 付き
 * {@code issuedAt.expiresAt.signature} 形式）。署名対象ペイロードにスコープ種別・正規化名称
 * （trim 済み）・操作者ユーザーID・候補ID集合（順序非依存にするためソート済み）・issuedAt/expiresAt
 * を連結して含めることで、発行時と同一のコンテキストでのみ検証が通る。</p>
 *
 * <p>署名鍵は {@code mannschaft.duplicate-name.fingerprint-secret} を直接使う。未設定時は
 * {@code mannschaft.jwt.secret} をそのまま使う（金型: {@code AdUnsubscribeJwtService} の
 * フォールバック方針を踏襲。本サービスは JWT ではなく単純 HMAC のため鍵導出は不要）。</p>
 */
@Service
public class DuplicateNameFingerprintServiceImpl implements DuplicateNameFingerprintService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** fingerprint の有効期限（秒）。金型 QrTokenService の QR_TOKEN_EXPIRY_SECONDS と同値。 */
    private static final long TTL_SECONDS = 300;

    private final String secret;

    public DuplicateNameFingerprintServiceImpl(
            @Value("${mannschaft.duplicate-name.fingerprint-secret:}") String fingerprintSecret,
            @Value("${mannschaft.jwt.secret}") String fallbackSecret) {
        this.secret = (fingerprintSecret == null || fingerprintSecret.isBlank())
                ? fallbackSecret
                : fingerprintSecret;
    }

    @Override
    public String issue(DuplicateNameScopeKind scopeKind, String normalizedName, Long actorUserId,
            List<String> candidateIds) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + TTL_SECONDS;
        String payload = buildPayload(scopeKind, normalizedName, actorUserId, candidateIds, issuedAt, expiresAt);
        String signature = sign(payload);
        return issuedAt + "." + expiresAt + "." + signature;
    }

    @Override
    public boolean verify(String fingerprint, DuplicateNameScopeKind scopeKind, String normalizedName,
            Long actorUserId, List<String> candidateIds) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        String[] parts = fingerprint.split("\\.", 3);
        if (parts.length != 3) {
            return false;
        }
        long issuedAt;
        long expiresAt;
        try {
            issuedAt = Long.parseLong(parts[0]);
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        String signature = parts[2];

        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }

        String expectedPayload =
                buildPayload(scopeKind, normalizedName, actorUserId, candidateIds, issuedAt, expiresAt);
        String expectedSignature = sign(expectedPayload);
        return constantTimeEquals(expectedSignature, signature);
    }

    /**
     * 署名対象ペイロードを組み立てる。候補ID集合は呼び出し順序に依存させないためソートする
     * （候補供給コールバックが返す順序が実行のたびに変わっても、集合として同一なら一致判定できる）。
     */
    private String buildPayload(DuplicateNameScopeKind scopeKind, String normalizedName, Long actorUserId,
            List<String> candidateIds, long issuedAt, long expiresAt) {
        String sortedCandidateIds = candidateIds.stream()
                .sorted()
                .collect(Collectors.joining(","));
        return scopeKind.name()
                + "|" + normalizedName.trim()
                + "|" + actorUserId
                + "|" + sortedCandidateIds
                + "|" + issuedAt
                + "|" + expiresAt;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmac);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 署名に失敗しました", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
