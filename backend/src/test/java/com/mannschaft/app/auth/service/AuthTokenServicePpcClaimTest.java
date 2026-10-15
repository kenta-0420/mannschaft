package com.mannschaft.app.auth.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F01.9 保護者同意ゲート: {@link AuthTokenService} が発行するアクセストークンの {@code ppc} クレームを検証する。
 *
 * <p>受け入れ条件 AC-15（発行 access_token に ppc==true / ACTIVE は false・不在）を担保する。
 * また AC-14（PENDING ユーザーでもトークンは発行される＝発行自体は失敗しない）を、
 * ppc==true でのトークン生成が成功することで担保する。Docker 非依存（Valkey はモック・fail-open）。</p>
 */
@DisplayName("AuthTokenService ppc クレーム (F01.9)")
class AuthTokenServicePpcClaimTest {

    /** HS256 に必要な 256bit 以上（32byte 以上）のダミー鍵。 */
    private static final String SECRET = "test-secret-key-for-ppc-claim-unit-test-0123456789abcdef";

    private AuthTokenService newService() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // Valkey はモック（hasKey=null→false / opsForValue()=null→NPE を fail-open で握る）ため
        // ブラックリスト・無効化チェックは常に通過する。
        return new AuthTokenService(redis, SECRET, 900L, 1_209_600L);
    }

    @Test
    @DisplayName("AC-14/AC-15: ppc=true で発行したトークンは ppc==true クレームを持つ")
    void issue_with_ppc_true_sets_claim() {
        AuthTokenService service = newService();
        String token = service.issueAccessToken(100L, List.of("MEMBER"), true);

        Claims claims = service.parseAccessToken(token);
        assertThat(claims.get("ppc", Boolean.class)).isTrue();
        assertThat(claims.getSubject()).isEqualTo("100");
    }

    @Test
    @DisplayName("AC-15: ppc=false で発行したトークンは ppc==false クレームを持つ")
    void issue_with_ppc_false_sets_claim() {
        AuthTokenService service = newService();
        String token = service.issueAccessToken(100L, List.of("MEMBER"), false);

        Claims claims = service.parseAccessToken(token);
        assertThat(claims.get("ppc", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("AC-15: 後方互換オーバーロード（ppc 引数なし）は ppc==false")
    void legacy_overload_defaults_ppc_false() {
        AuthTokenService service = newService();
        String token = service.issueAccessToken(100L, List.of("MEMBER"));

        Claims claims = service.parseAccessToken(token);
        assertThat(claims.get("ppc", Boolean.class)).isFalse();
    }
}
