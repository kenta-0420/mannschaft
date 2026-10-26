package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdUnsubscribeJwtService} 単体テスト（F09.17 Phase 11-b）。
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>generate → verify ラウンドトリップ成功</li>
 *   <li>exp 経過 → AD_UNSUBSCRIBE_TOKEN_EXPIRED</li>
 *   <li>署名鍵不一致 → AD_UNSUBSCRIBE_TOKEN_INVALID</li>
 *   <li>不正な channel → 生成時に IllegalArgumentException</li>
 * </ul>
 */
@DisplayName("AdUnsubscribeJwtService 単体テスト")
class AdUnsubscribeJwtServiceTest {

    private static final String SECRET = "test-unsubscribe-secret-256bit-minimum-1234567890";
    private AdUnsubscribeJwtService service;

    @BeforeEach
    void setUp() {
        service = new AdUnsubscribeJwtService(SECRET, "fallback-key-also-256bit-minimum-12345678");
    }

    @Test
    @DisplayName("generate → verify が成功し claims が復元できる")
    void generateAndVerifySuccess() {
        String token = service.generate(1001L, 3, "EMAIL");

        AdUnsubscribeJwtService.UnsubscribeTokenClaims claims = service.verify(token);

        assertThat(claims.userId()).isEqualTo(1001L);
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.channel()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("4 つの channel いずれも generate/verify 可能")
    void allChannelsAccepted() {
        for (String ch : new String[]{"ANNOUNCEMENT", "EMAIL", "PUSH", "BANNER"}) {
            String token = service.generate(42L, 0, ch);
            assertThat(service.verify(token).channel()).isEqualTo(ch);
        }
    }

    @Test
    @DisplayName("期限切れトークン → AD_UNSUBSCRIBE_TOKEN_EXPIRED (410 マッピング)")
    void expiredTokenRejected() {
        // 過去 exp で手動署名
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .issuer("mannschaft-ads")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .claim("uid", 1L)
                .claim("ver", 0)
                .claim("ch", "EMAIL")
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> service.verify(expired))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("署名鍵不一致トークン → AD_UNSUBSCRIBE_TOKEN_INVALID")
    void tamperedTokenRejected() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "different-key-256bit-minimum-1234567890-abcdef".getBytes(StandardCharsets.UTF_8));
        String tampered = Jwts.builder()
                .issuer("mannschaft-ads")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .claim("uid", 1L)
                .claim("ver", 0)
                .claim("ch", "EMAIL")
                .signWith(wrongKey)
                .compact();

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
    }

    @Test
    @DisplayName("空文字トークン → AD_UNSUBSCRIBE_TOKEN_INVALID")
    void emptyTokenRejected() {
        assertThatThrownBy(() -> service.verify(""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
    }

    @Test
    @DisplayName("不正な channel での generate は IllegalArgumentException")
    void invalidChannelRejected() {
        assertThatThrownBy(() -> service.generate(1L, 0, "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("専用鍵未設定でも fallback (mannschaft.jwt.secret) から派生鍵が生成され動作する")
    void fallbackKeyDerivation() {
        AdUnsubscribeJwtService fallback = new AdUnsubscribeJwtService(
                "", "fallback-jwt-secret-256bit-minimum-1234567890-abcdef");
        String token = fallback.generate(99L, 0, "PUSH");
        assertThat(fallback.verify(token).userId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("専用鍵と fallback鍵で署名したトークンは相互に検証不能（鍵分離の確認）")
    void keysAreIsolated() {
        AdUnsubscribeJwtService fallback = new AdUnsubscribeJwtService(
                "", "fallback-jwt-secret-256bit-minimum-1234567890-abcdef");
        String tokenFromDedicated = service.generate(1L, 0, "EMAIL");

        assertThatThrownBy(() -> fallback.verify(tokenFromDedicated))
                .isInstanceOf(BusinessException.class);
    }
}
