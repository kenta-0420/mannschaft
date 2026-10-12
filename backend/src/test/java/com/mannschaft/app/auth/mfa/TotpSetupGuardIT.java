package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.event.MfaEnabledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: TOTP 有効化確定（{@code POST /api/v1/auth/2fa/verify}）の有効化済みガード。
 *
 * <p>穴: {@code Auth2faService#verifyTotpSetup} に有効化済みガードが無く、有効化済みユーザーが正しい
 * TOTP を送ると再び有効化され、<b>バックアップコードが黙って全て差し替わり</b>（手元の印刷済みコードが無効化）、
 * {@code MfaEnabledEvent} が二重発行されていた。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 TOTP 有効化確定の有効化済みガード（AC-1）")
class TotpSetupGuardIT extends MfaItSupport {

    private static final String VERIFY_PATH = "/api/v1/auth/2fa/verify";

    @Autowired
    private ApplicationEvents events;

    /**
     * AC-1: 有効化済みで POST /2fa/verify → 409（AUTH_078）。バックアップコードは不変、MfaEnabledEvent は出ない。
     */
    @Test
    @DisplayName("AC-1 有効化済みでverifyすると409・バックアップコード不変・有効化イベント無し")
    void 有効化済みでverifyすると409でバックアップコードは不変() throws Exception {
        MfaUser u = createTotpUser();
        String before = backupCodesJson(u.userId());

        MvcResult result = mockMvc.perform(post(VERIFY_PATH).servletPath(VERIFY_PATH)
                        .with(user(String.valueOf(u.userId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("totpCode", currentTotp(u.totpSecret())))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("AC-1 有効化済みは 409: %s", body(result)).isEqualTo(409);
        assertThat(errorCode(result)).as("AC-1 有効化済みのエラーコード").isEqualTo("AUTH_078");
        assertThat(backupCodesJson(u.userId())).as("AC-1 バックアップコードは差し替わらない").isEqualTo(before);
        assertThat(events.stream(MfaEnabledEvent.class).count()).as("AC-1 MfaEnabledEvent は発行されない").isZero();
    }
}
