package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.controller.Auth2faController;
import com.mannschaft.app.common.security.IntentionallyPublic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: 新公開 EP {@code POST /api/v1/auth/2fa/email/send} の公開契約（AC-18）。
 *
 * <p>docs/security/public_api_addition_checklist.md §5 に従い、「permitAll で未認証到達できる」
 * 「{@code @IntentionallyPublic} が付いている」「MFA セッショントークンを本文で受ける（クエリ文字列では受けない）」
 * 「レート制限が実際に効く」を実 Security フィルタ越しに実測する。</p>
 *
 * <p>レート制限の数値は試練側で「同一 IP から 30 回以内に 429」と上限を置く（設計上の IP 上限は乙隊が確定する。
 * 30 回を超えて素通しするなら総当たり・メール爆撃の防御として不十分）。不正なセッショントークンでも
 * IP 上限に計上されること＝レート制限がセッション照合より前に効くことも併せて要求する
 * （トークン推測の総当たりを IP 上限で止めるため）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 メールOTP 送信 公開EPの契約（AC-18）")
class MfaEmailSendPublicEndpointIT extends MfaItSupport {

    private static final String PATH = "/api/v1/auth/2fa/email/send";
    /** 同一 IP からこの回数以内に 429 が出ること（試練側の上限）。 */
    private static final int IP_LIMIT_CEILING = 30;

    private MvcResult send(String remoteAddr, String token) throws Exception {
        return mockMvc.perform(post(PATH).servletPath(PATH)
                        .with(req -> {
                            req.setRemoteAddr(remoteAddr);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaSessionToken", token))))
                .andReturn();
    }

    /** AC-18: 未認証で到達でき（401/403 にならない）、不正なセッショントークンは AUTH_073 で弾かれる。 */
    @Test
    @DisplayName("AC-18 未認証で到達でき不正セッションはAUTH_073")
    void 未認証で到達でき不正セッションは弾かれる() throws Exception {
        MvcResult r = send("198.51.100.21", UUID.randomUUID().toString());

        assertThat(r.getResponse().getStatus()).as("AC-18 permitAll（401/403 ではない）: %s", body(r))
                .isNotIn(401, 403);
        assertThat(r.getResponse().getStatus()).as("AC-18 不正セッション: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_073");
    }

    /** AC-18: MFA セッショントークンはクエリ文字列では受けない（本文必須。本文なしは 400）。 */
    @Test
    @DisplayName("AC-18 セッショントークンをクエリ文字列で渡しても受け付けない")
    void クエリ文字列のトークンは受け付けない() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult r = mockMvc.perform(post(PATH).servletPath(PATH)
                        .param("mfaSessionToken", session)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as("AC-18 本文なし: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("COMMON_001");
    }

    /** AC-18: {@code @IntentionallyPublic} がこのパスを引用して付与されている。 */
    @Test
    @DisplayName("AC-18 IntentionallyPublicが付与されている")
    void IntentionallyPublicが付いている() {
        Method endpoint = Arrays.stream(Auth2faController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains("/email/send"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AC-18 POST /email/send のハンドラが無い"));

        IntentionallyPublic marker = endpoint.getAnnotation(IntentionallyPublic.class);
        assertThat(marker).as("AC-18 @IntentionallyPublic").isNotNull();
        assertThat(marker.value()).contains(PATH);
    }

    /**
     * AC-18: 同一 IP からの連打は {@value #IP_LIMIT_CEILING} 回以内に 429 になる（不正トークンでも計上）。
     * 別 IP は影響を受けない。
     */
    @Test
    @DisplayName("AC-18 同一IPからの連打は30回以内に429・別IPは影響なし")
    void 同一IP連打は429() throws Exception {
        String ip = "198.51.100.77";
        int firstLimited = -1;
        for (int i = 1; i <= IP_LIMIT_CEILING + 1; i++) {
            MvcResult r = send(ip, UUID.randomUUID().toString());
            if (r.getResponse().getStatus() == 429) {
                firstLimited = i;
                break;
            }
        }

        assertThat(firstLimited).as("AC-18 同一 IP から %d 回以内に 429", IP_LIMIT_CEILING)
                .isBetween(2, IP_LIMIT_CEILING);
        MvcResult other = send("198.51.100.78", UUID.randomUUID().toString());
        assertThat(other.getResponse().getStatus()).as("AC-18 別 IP は影響なし: %s", body(other)).isNotEqualTo(429);
    }
}
