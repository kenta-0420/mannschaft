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
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * F09.17 Phase 11-b — 広告 unsubscribe ワンクリック解除用 JWT サービス。
 *
 * <p>メール footer に埋め込む解除用リンク（{@code GET /api/v1/ads/unsubscribe?token=...}）の
 * トークン生成・検証を担う。HS256 / exp=30 日 / claims = {uid, ver, ch}。</p>
 *
 * <p>{@code ver}（{@code unsubscribe_token_version}）は {@link UserAdPreferenceService} で管理し、
 * 受信設定変更時にローテートすることで過去トークンを一括失効させる。</p>
 *
 * <p>署名鍵は {@code mannschaft.ad.jwt.unsubscribe-secret} を直接使う。
 * 未設定時は {@code mannschaft.jwt.secret} から HMAC-SHA256 で派生鍵を生成する（運用時は明示設定推奨）。</p>
 */
@Slf4j
@Service
public class AdUnsubscribeJwtService {

    private static final String ISSUER = "mannschaft-ads";
    /** uid: user_id (Long) */
    private static final String CLAIM_USER_ID = "uid";
    /** ver: unsubscribe_token_version (Integer) */
    private static final String CLAIM_TOKEN_VERSION = "ver";
    /** ch: channel ("ANNOUNCEMENT" / "EMAIL" / "PUSH" / "BANNER") */
    private static final String CLAIM_CHANNEL = "ch";

    /** 許容 channel 値（設計書 §6）。 */
    public static final Set<String> ALLOWED_CHANNELS = Set.of("ANNOUNCEMENT", "EMAIL", "PUSH", "BANNER");

    /** トークン有効期限: 30 日。 */
    public static final Duration TOKEN_TTL = Duration.ofDays(30);

    private final SecretKey signingKey;

    public AdUnsubscribeJwtService(
            @Value("${mannschaft.ad.jwt.unsubscribe-secret:}") String unsubscribeSecret,
            @Value("${mannschaft.jwt.secret}") String fallbackSecret) {
        this.signingKey = resolveSigningKey(unsubscribeSecret, fallbackSecret);
    }

    /**
     * unsubscribe JWT を発行する。
     *
     * @param userId       ユーザー ID（必須）
     * @param tokenVersion {@code user_ad_preferences.unsubscribe_token_version}（必須）
     * @param channel      対象チャネル（"ANNOUNCEMENT"/"EMAIL"/"PUSH"/"BANNER"）
     * @return JWT 文字列
     */
    public String generate(Long userId, Integer tokenVersion, String channel) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (tokenVersion == null) {
            throw new IllegalArgumentException("tokenVersion must not be null");
        }
        if (channel == null || !ALLOWED_CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("channel must be one of " + ALLOWED_CHANNELS);
        }
        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_TTL);
        return Jwts.builder()
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(CLAIM_CHANNEL, channel)
                .signWith(signingKey)
                .compact();
    }

    /**
     * unsubscribe JWT を検証する。
     *
     * <p>失敗パターン:</p>
     * <ul>
     *   <li>期限切れ → {@link AdCampaignErrorCode#AD_UNSUBSCRIBE_TOKEN_EXPIRED} (410)</li>
     *   <li>改竄 / 形式不正 → {@link AdCampaignErrorCode#AD_UNSUBSCRIBE_TOKEN_INVALID} (400)</li>
     * </ul>
     *
     * <p>token_version 不一致は呼び出し側 ({@link UserAdPreferenceService#unsubscribe})
     * で発火する設計（DB ロード後でないと判定不可のため）。</p>
     *
     * @param token JWT 文字列
     * @return 検証済 claims
     * @throws BusinessException 検証失敗時
     */
    public UnsubscribeTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
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
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID, e);
        }

        Long userId = readLongClaim(claims, CLAIM_USER_ID);
        Integer tokenVersion = readIntClaim(claims, CLAIM_TOKEN_VERSION);
        String channel = claims.get(CLAIM_CHANNEL, String.class);
        if (userId == null || tokenVersion == null || channel == null
                || !ALLOWED_CHANNELS.contains(channel)) {
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
        }
        return new UnsubscribeTokenClaims(userId, tokenVersion, channel);
    }

    private Long readLongClaim(Claims claims, String key) {
        Object raw = claims.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer readIntClaim(Claims claims, String key) {
        Object raw = claims.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 専用秘密鍵があれば使う。無ければ {@code mannschaft.jwt.secret} から
     * HMAC-SHA256("ad-unsubscribe") で派生鍵を生成する。
     */
    private static SecretKey resolveSigningKey(String dedicatedSecret, String fallbackSecret) {
        if (dedicatedSecret != null && !dedicatedSecret.isBlank()) {
            return Keys.hmacShaKeyFor(dedicatedSecret.getBytes(StandardCharsets.UTF_8));
        }
        if (fallbackSecret == null || fallbackSecret.isBlank()) {
            throw new IllegalStateException(
                    "mannschaft.ad.jwt.unsubscribe-secret も mannschaft.jwt.secret も未設定です");
        }
        byte[] derived = hmacSha256(fallbackSecret, "ad-unsubscribe");
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
     * unsubscribe JWT の検証済 claims。
     *
     * @param userId       受信者
     * @param tokenVersion 発行時の {@code unsubscribe_token_version}
     * @param channel      対象チャネル
     */
    public record UnsubscribeTokenClaims(Long userId, Integer tokenVersion, String channel) {
    }
}
