package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * F09.17 Phase 11-b — メール開封ピクセル用 JWT サービス。
 *
 * <p>HTML メールに埋め込む 1x1 GIF へのアクセスを認証するためのトークン。
 * HS256 / exp=180 日 / claims = {did, t}。</p>
 *
 * <p><strong>PII 保護（設計書 §6）</strong>: claims に {@code user_id} を含めない。
 * 開封トラッキングは {@code delivery_id} に紐づく集計だけを目的とし、
 * 受信者個人を URL クエリだけから特定できない設計とする。</p>
 *
 * <p>署名鍵は {@code mannschaft.ad.jwt.open-pixel-secret} を直接使う。
 * 未設定時は {@code mannschaft.jwt.secret} から HMAC-SHA256 で派生鍵を生成する。</p>
 */
@Slf4j
@Service
public class AdOpenPixelJwtService {

    private static final String ISSUER = "mannschaft-ads";
    /** did: delivery_id (UUID) */
    static final String CLAIM_DELIVERY_ID = "did";
    /** t: channel type ("ANNOUNCEMENT" / "EMAIL" / "PUSH" / "BANNER") */
    static final String CLAIM_TYPE = "t";

    public static final Set<String> ALLOWED_TYPES = Set.of("ANNOUNCEMENT", "EMAIL", "PUSH", "BANNER");

    /** トークン有効期限: 180 日。 */
    public static final Duration TOKEN_TTL = Duration.ofDays(180);

    private final SecretKey signingKey;

    public AdOpenPixelJwtService(
            @Value("${mannschaft.ad.jwt.open-pixel-secret:}") String openPixelSecret,
            @Value("${mannschaft.jwt.secret}") String fallbackSecret) {
        this.signingKey = resolveSigningKey(openPixelSecret, fallbackSecret);
    }

    /**
     * 開封ピクセル JWT を発行する。
     *
     * <p>本メソッドの引数に {@code user_id} を含めないことが PII 保護の根幹。
     * 呼び出し側 (delivery 発行時) は delivery_id だけを引いて生成すること。</p>
     *
     * @param deliveryId 配信 ID（必須）
     * @param type       channel type
     * @return JWT 文字列
     */
    public String generate(UUID deliveryId, String type) {
        if (deliveryId == null) {
            throw new IllegalArgumentException("deliveryId must not be null");
        }
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be one of " + ALLOWED_TYPES);
        }
        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_TTL);
        return Jwts.builder()
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_DELIVERY_ID, deliveryId.toString())
                .claim(CLAIM_TYPE, type)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 開封ピクセル JWT を検証する。
     *
     * <p>失敗時は {@link AdCampaignErrorCode#AD_OPEN_PIXEL_TOKEN_INVALID} を投げる。
     * Controller 側はこれを握り潰しつつログだけ残し、ピクセル本体は 200 GIF を返却する。</p>
     */
    public OpenPixelClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID);
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // 開封ピクセルは長期間メールに残るが、180 日超は集計対象外として扱う
            throw new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID, e);
        }

        String didRaw = claims.get(CLAIM_DELIVERY_ID, String.class);
        String type = claims.get(CLAIM_TYPE, String.class);
        if (didRaw == null || type == null || !ALLOWED_TYPES.contains(type)) {
            throw new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID);
        }
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(didRaw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID, e);
        }
        return new OpenPixelClaims(deliveryId, type);
    }

    private static SecretKey resolveSigningKey(String dedicatedSecret, String fallbackSecret) {
        if (dedicatedSecret != null && !dedicatedSecret.isBlank()) {
            return Keys.hmacShaKeyFor(dedicatedSecret.getBytes(StandardCharsets.UTF_8));
        }
        if (fallbackSecret == null || fallbackSecret.isBlank()) {
            throw new IllegalStateException(
                    "mannschaft.ad.jwt.open-pixel-secret も mannschaft.jwt.secret も未設定です");
        }
        byte[] derived = hmacSha256(fallbackSecret, "ad-open-pixel");
        return Keys.hmacShaKeyFor(derived);
    }

    private static byte[] hmacSha256(String key, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 key derivation failed", e);
        }
    }

    /**
     * 開封ピクセル JWT の検証済 claims。
     *
     * <p><strong>user_id を含めない</strong>（PII 保護）。フィールドは {@link #deliveryId} と {@link #type} のみ。</p>
     */
    public record OpenPixelClaims(UUID deliveryId, String type) {
    }
}
