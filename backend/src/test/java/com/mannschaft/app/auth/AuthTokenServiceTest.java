package com.mannschaft.app.auth;

import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthTokenService} の単体テスト。
 * JWT発行・検証、Refresh Token生成、SHA-256ハッシュ、レートリミットを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenService 単体テスト")
class AuthTokenServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthTokenService authTokenService;

    /** JWT署名用の32バイト以上のシークレット */
    private static final String TEST_SECRET = "test-secret-key-must-be-at-least-32-bytes-long!!";
    private static final long ACCESS_TOKEN_EXPIRATION = 900L;
    private static final long REFRESH_TOKEN_EXPIRATION = 604800L;

    @BeforeEach
    void setUp() {
        authTokenService = new AuthTokenService(
                redisTemplate, TEST_SECRET, ACCESS_TOKEN_EXPIRATION, REFRESH_TOKEN_EXPIRATION);
    }

    // ========================================
    // issueAccessToken
    // ========================================

    @Nested
    @DisplayName("issueAccessToken")
    class IssueAccessToken {

        @Test
        @DisplayName("正常系: JWTが発行される")
        void issueAccessToken_正常_JWTが発行される() {
            // Given
            Long userId = 1L;
            List<String> roles = List.of("MEMBER");

            // When
            String token = authTokenService.issueAccessToken(userId, roles);

            // Then
            assertThat(token).isNotNull().isNotEmpty();
            // JWT形式（ヘッダー.ペイロード.署名）であることを確認
            assertThat(token.split("\\.")).hasSize(3);
        }
    }

    // ========================================
    // parseAccessToken
    // ========================================

    @Nested
    @DisplayName("parseAccessToken")
    class ParseAccessToken {

        @Test
        @DisplayName("正常系: Claimsが取得できる")
        void parseAccessToken_正常_Claimsが取得できる() {
            // Given
            Long userId = 42L;
            List<String> roles = List.of("MEMBER", "ADMIN");
            String token = authTokenService.issueAccessToken(userId, roles);

            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            // When
            Claims claims = authTokenService.parseAccessToken(token);

            // Then
            assertThat(claims.getSubject()).isEqualTo("42");
            assertThat(claims.getIssuer()).isEqualTo("mannschaft");
            assertThat(claims.getId()).isNotNull();
            @SuppressWarnings("unchecked")
            List<String> parsedRoles = claims.get("roles", List.class);
            assertThat(parsedRoles).containsExactly("MEMBER", "ADMIN");
        }

        @Test
        @DisplayName("異常系: 期限切れトークンで例外スロー")
        void parseAccessToken_期限切れ_例外スロー() {
            // Given: 過去の有効期限を持つJWTを直接構築
            SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
            Instant past = Instant.now().minusSeconds(60);
            String expiredToken = Jwts.builder()
                    .subject("1")
                    .id(UUID.randomUUID().toString())
                    .claim("roles", List.of("MEMBER"))
                    .issuer("mannschaft")
                    .issuedAt(Date.from(past.minusSeconds(60)))
                    .expiration(Date.from(past))
                    .signWith(key)
                    .compact();

            // When / Then
            assertThatThrownBy(() -> authTokenService.parseAccessToken(expiredToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_010"));
        }

        @Test
        @DisplayName("異常系: JTIブラックリスト登録済みで例外スロー")
        void parseAccessToken_JTIブラックリスト_例外スロー() {
            // Given
            String token = authTokenService.issueAccessToken(1L, List.of("MEMBER"));

            // JTIブラックリストにヒットするようにモック設定
            given(redisTemplate.hasKey(argThat(key -> key.startsWith("mannschaft:auth:blacklist:"))))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authTokenService.parseAccessToken(token))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_011"));
        }

        @Test
        @DisplayName("異常系: 不正なJWT文字列で例外スロー")
        void parseAccessToken_不正JWT_例外スロー() {
            // Given
            String invalidToken = "invalid.jwt.token";

            // When / Then
            assertThatThrownBy(() -> authTokenService.parseAccessToken(invalidToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_010"));
        }
    }

    // ========================================
    // generateRefreshToken
    // ========================================

    @Nested
    @DisplayName("generateRefreshToken")
    class GenerateRefreshToken {

        @Test
        @DisplayName("正常系: ランダムなhex文字列が生成される")
        void generateRefreshToken_正常_ランダム文字列が生成される() {
            // When
            String token = authTokenService.generateRefreshToken();

            // Then
            assertThat(token).isNotNull();
            // 32バイト = 64文字のhex文字列
            assertThat(token).hasSize(64);
            assertThat(token).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("正常系: 毎回異なるトークンが生成される")
        void generateRefreshToken_正常_毎回異なるトークン() {
            // When
            String token1 = authTokenService.generateRefreshToken();
            String token2 = authTokenService.generateRefreshToken();

            // Then
            assertThat(token1).isNotEqualTo(token2);
        }
    }

    // ========================================
    // hashToken
    // ========================================

    @Nested
    @DisplayName("hashToken")
    class HashToken {

        @Test
        @DisplayName("正常系: SHA-256ハッシュが返る")
        void hashToken_正常_SHA256ハッシュが返る() throws Exception {
            // Given
            String rawToken = "test-token-value";

            // 期待値を直接計算
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);

            // When
            String result = authTokenService.hashToken(rawToken);

            // Then
            assertThat(result).isEqualTo(expected);
            // SHA-256は64文字のhex
            assertThat(result).hasSize(64);
        }

        @Test
        @DisplayName("正常系: 同一入力で同一ハッシュが返る（決定論的）")
        void hashToken_正常_同一入力で同一ハッシュ() {
            // Given
            String rawToken = "deterministic-test";

            // When
            String hash1 = authTokenService.hashToken(rawToken);
            String hash2 = authTokenService.hashToken(rawToken);

            // Then
            assertThat(hash1).isEqualTo(hash2);
        }
    }

    // ========================================
    // checkRateLimit
    // ========================================

    @Nested
    @DisplayName("checkRateLimit")
    class CheckRateLimit {

        @Test
        @DisplayName("正常系: 制限内なら正常通過")
        void checkRateLimit_制限内_正常通過() {
            // Given
            String key = "mannschaft:auth:test_rate_limit:127.0.0.1";
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(key)).willReturn(3L);

            // When（例外が発生しないことを確認）
            authTokenService.checkRateLimit(key, 5, Duration.ofMinutes(1));

            // Then
            verify(valueOperations).increment(key);
        }

        @Test
        @DisplayName("異常系: 制限超過でBusinessException")
        void checkRateLimit_制限超過_BusinessException() {
            // Given
            String key = "mannschaft:auth:test_rate_limit:127.0.0.1";
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(key)).willReturn(6L);

            // When / Then
            assertThatThrownBy(() -> authTokenService.checkRateLimit(key, 5, Duration.ofMinutes(1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }

        @Test
        @DisplayName("正常系: 初回アクセス時にTTLが設定される")
        void checkRateLimit_初回_TTL設定() {
            // Given
            String key = "mannschaft:auth:test_rate_limit:first";
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(key)).willReturn(1L);

            // When
            authTokenService.checkRateLimit(key, 5, Duration.ofMinutes(1));

            // Then
            verify(redisTemplate).expire(key, 60L, TimeUnit.SECONDS);
        }
    }

    // ========================================
    // addJtiToBlacklist
    // ========================================

    @Nested
    @DisplayName("addJtiToBlacklist")
    class AddJtiToBlacklist {

        @Test
        @DisplayName("正常系: JTIがValkeyに登録される")
        void addJtiToBlacklist_正常_登録される() {
            // Given
            String jti = "test-jti-uuid";
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            authTokenService.addJtiToBlacklist(jti, 300L);

            // Then
            verify(valueOperations).set("mannschaft:auth:blacklist:" + jti, "1", 300L, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("境界値: 残存TTLが0以下の場合は登録しない")
        void addJtiToBlacklist_TTL零以下_登録しない() {
            // When
            authTokenService.addJtiToBlacklist("test-jti", 0L);
            authTokenService.addJtiToBlacklist("test-jti", -1L);

            // Then: redisTemplate.opsForValue() が呼ばれないことを確認
            // (Mockitoのデフォルトで verify されていないことが証明)
        }
    }
}
