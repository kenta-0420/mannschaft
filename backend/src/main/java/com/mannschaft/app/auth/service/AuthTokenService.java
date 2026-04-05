package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT発行・検証およびValkey（Redis互換）を利用したトークン管理サービス。
 * Access Token（JWT HS256）とRefresh Token（Opaque SHA-256）の発行・検証・無効化、
 * レートリミット機能を提供する。
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class AuthTokenService {

    private static final String ISSUER = "mannschaft";
    private static final String BLACKLIST_KEY_PREFIX = "mannschaft:auth:blacklist:";
    private static final String USER_INVALIDATED_KEY_PREFIX = "mannschaft:auth:user_invalidated_at:";
    private static final long USER_INVALIDATION_TTL_SECONDS = 900L;

    private final StringRedisTemplate redisTemplate;
    private final SecretKey signingKey;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;
    private final SecureRandom secureRandom;

    public AuthTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${mannschaft.jwt.secret}") String secret,
            @Value("${mannschaft.jwt.access-token-expiration}") long accessTokenExpirationSeconds,
            @Value("${mannschaft.jwt.refresh-token-expiration}") long refreshTokenExpirationSeconds) {
        this.redisTemplate = redisTemplate;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.secureRandom = new SecureRandom();
    }

    // ========================================
    // Access Token (JWT HS256)
    // ========================================

    /**
     * Access Token を発行する。
     *
     * @param userId ユーザーID
     * @param roles  ロール一覧
     * @return JWT文字列
     */
    public String issueAccessToken(Long userId, List<String> roles) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = now.plusSeconds(accessTokenExpirationSeconds);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .issuer(ISSUER)
                .issuedAt(toDate(now))
                .expiration(toDate(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Access Token を検証し、Claims を返す。
     * 署名検証・有効期限チェック・Valkeyブラックリスト確認を行う。
     *
     * @param token JWT文字列
     * @return 検証済みClaims
     * @throws BusinessException トークン無効時
     */
    public Claims parseAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(AuthErrorCode.AUTH_010, e);
        } catch (JwtException e) {
            throw new BusinessException(AuthErrorCode.AUTH_010, e);
        }

        String jti = claims.getId();
        Long userId = Long.valueOf(claims.getSubject());
        long iat = claims.getIssuedAt().getTime() / 1000;

        // JTIブラックリスト確認（個別ログアウト対応）
        if (isJtiBlacklisted(jti)) {
            throw new BusinessException(AuthErrorCode.AUTH_011);
        }

        // ユーザー全デバイス無効化確認
        if (isTokenInvalidated(userId, iat)) {
            throw new BusinessException(AuthErrorCode.AUTH_012);
        }

        return claims;
    }

    /**
     * Access Token の有効期限（秒）を返す。
     */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    /**
     * Refresh Token の有効期限（秒）を返す。
     */
    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }

    // ========================================
    // Refresh Token (Opaque SHA-256)
    // ========================================

    /**
     * Refresh Token（平文）を生成する。SecureRandom で32バイトを生成し、hex文字列として返す。
     *
     * @return 平文トークン文字列
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * トークン文字列をSHA-256でハッシュ化する。DB保存用。
     *
     * @param rawToken 平文トークン
     * @return SHA-256ハッシュ値（hex文字列）
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256は全JVMでサポート必須のため到達不能
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    // ========================================
    // Valkey 操作
    // ========================================

    /**
     * JTIをブラックリストに追加する。個別ログアウト時に使用。
     *
     * @param jti                JTI（JWT ID）
     * @param remainingTtlSeconds 残存有効期限（秒）
     */
    public void addJtiToBlacklist(String jti, long remainingTtlSeconds) {
        if (remainingTtlSeconds <= 0) {
            return;
        }
        String key = BLACKLIST_KEY_PREFIX + jti;
        redisTemplate.opsForValue().set(key, "1", remainingTtlSeconds, TimeUnit.SECONDS);
    }

    /**
     * ユーザーの全トークン無効化タイムスタンプを設定する。全デバイスログアウト時に使用。
     * このタイムスタンプ以前に発行されたAccess Tokenは全て無効となる。
     *
     * @param userId ユーザーID
     */
    public void setUserInvalidationTimestamp(Long userId) {
        String key = USER_INVALIDATED_KEY_PREFIX + userId;
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        redisTemplate.opsForValue().set(key, String.valueOf(now), USER_INVALIDATION_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * JTIがブラックリストに登録されているかチェックする。
     *
     * @param jti JTI（JWT ID）
     * @return ブラックリスト登録済みの場合true
     */
    public boolean isJtiBlacklisted(String jti) {
        String key = BLACKLIST_KEY_PREFIX + jti;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * ユーザーのトークンが全デバイス無効化されているかチェックする。
     * user_invalidated_at が存在し、トークンの発行時刻（iat）がそれより前の場合にtrueを返す。
     *
     * @param userId          ユーザーID
     * @param iatEpochSeconds トークン発行時刻（epoch秒）
     * @return 無効化対象の場合true
     */
    public boolean isTokenInvalidated(Long userId, long iatEpochSeconds) {
        String key = USER_INVALIDATED_KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return false;
        }
        long invalidatedAt = Long.parseLong(value);
        return iatEpochSeconds < invalidatedAt;
    }

    // ========================================
    // レートリミット（汎用）
    // ========================================

    /**
     * レートリミットをチェックし、超過時は例外をスローする。
     * INCR + EXPIRE パターンでValkeyのカウンタを管理する。
     *
     * @param key         Valkeyキー
     * @param maxAttempts 最大試行回数
     * @param window      ウィンドウ期間
     * @throws BusinessException レートリミット超過時（AUTH_031）
     */
    public void checkRateLimit(String key, int maxAttempts, Duration window) {
        long currentCount = incrementRateLimit(key, window);
        if (currentCount > maxAttempts) {
            throw new BusinessException(AuthErrorCode.AUTH_031);
        }
    }

    /**
     * レートリミットカウンタをインクリメントし、現在のカウント値を返す。
     * キーが存在しない場合はウィンドウ期間のTTLを設定する。
     *
     * @param key    Valkeyキー
     * @param window ウィンドウ期間
     * @return インクリメント後のカウント値
     */
    public long incrementRateLimit(String key, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            count = 1L;
        }
        // 初回インクリメント時のみTTLを設定
        if (count == 1L) {
            redisTemplate.expire(key, window.getSeconds(), TimeUnit.SECONDS);
        }
        return count;
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private Date toDate(LocalDateTime dateTime) {
        return Date.from(dateTime.toInstant(ZoneOffset.UTC));
    }
}
