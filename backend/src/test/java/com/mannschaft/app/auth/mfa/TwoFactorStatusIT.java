package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261012-0910 試練: 2FA 設定状態 API（{@code GET /api/v1/auth/2fa/status}）。
 *
 * <p>設定画面が「未設定 / 有効(TOTP) / 有効(メール)＋残数」を出すための本人専用 API。
 * 秘密鍵（平文・暗号文とも）とバックアップコードのハッシュは決して返さない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA 設定状態 API（AC-4）")
class TwoFactorStatusIT extends MfaItSupport {

    private static final String STATUS_PATH = "/api/v1/auth/2fa/status";

    private MvcResult status(long userId) throws Exception {
        return mockMvc.perform(get(STATUS_PATH).servletPath(STATUS_PATH).with(user(String.valueOf(userId))))
                .andReturn();
    }

    /** AC-4: 2FA 未設定（行なし）は enabled=false・method=null・残数 0。 */
    @Test
    @DisplayName("AC-4 未設定ユーザーは enabled=false・method=null・残数0")
    void 未設定ユーザーは無効と返る() throws Exception {
        long userId = createPlainUser();

        MvcResult result = status(userId);

        assertThat(result.getResponse().getStatus()).as("AC-4: %s", body(result)).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("enabled").asBoolean(true)).as("AC-4 enabled").isFalse();
        assertThat(data.path("method").isNull() || data.path("method").isMissingNode()).as("AC-4 method=null").isTrue();
        assertThat(data.path("backupCodesRemaining").asInt(-1)).as("AC-4 残数").isZero();
    }

    /** AC-4: TOTP 有効は enabled=true・method=TOTP・残数 8。秘密鍵・ハッシュは返さない。 */
    @Test
    @DisplayName("AC-4 TOTP有効ユーザーは TOTP・残数8で秘密鍵とハッシュを返さない")
    void TOTP有効ユーザーは方式と残数を返し秘密を返さない() throws Exception {
        MfaUser u = createTotpUser();
        String encryptedSecret = jdbcTemplate.queryForObject(
                "SELECT totp_secret FROM two_factor_auth WHERE user_id = ?", String.class, u.userId());

        MvcResult result = status(u.userId());

        assertThat(result.getResponse().getStatus()).as("AC-4: %s", body(result)).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("enabled").asBoolean(false)).as("AC-4 enabled").isTrue();
        assertThat(data.path("method").asText(null)).as("AC-4 method").isEqualTo("TOTP");
        assertThat(data.path("backupCodesRemaining").asInt(-1)).as("AC-4 残数").isEqualTo(8);
        String raw = body(result);
        assertThat(raw).as("AC-4 平文秘密鍵を返さない").doesNotContain(u.totpSecret());
        assertThat(raw).as("AC-4 暗号化秘密鍵を返さない").doesNotContain(encryptedSecret);
        assertThat(raw).as("AC-4 バックアップコードのハッシュを返さない")
                .doesNotContain("argon2").doesNotContain("$2a$").doesNotContain("$2b$");
        u.backupCodes().forEach(code -> assertThat(raw).as("AC-4 平文バックアップコードを返さない").doesNotContain(code));
    }

    /** AC-4: EMAIL 有効は method=EMAIL。 */
    @Test
    @DisplayName("AC-4 メール方式ユーザーは method=EMAIL")
    void メール方式ユーザーはEMAILと返る() throws Exception {
        MfaUser u = createEmailUser();

        MvcResult result = status(u.userId());

        assertThat(result.getResponse().getStatus()).as("AC-4: %s", body(result)).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("enabled").asBoolean(false)).isTrue();
        assertThat(data.path("method").asText(null)).as("AC-4 method").isEqualTo("EMAIL");
        assertThat(data.path("backupCodesRemaining").asInt(-1)).isEqualTo(8);
    }

    /** AC-4: 本人のみ。未認証は 401。 */
    @Test
    @DisplayName("AC-4 未認証は401")
    void 未認証は401() throws Exception {
        MvcResult result = mockMvc.perform(get(STATUS_PATH).servletPath(STATUS_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).as("AC-4 未認証").isEqualTo(401);
    }
}
