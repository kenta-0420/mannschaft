package com.mannschaft.app.auth.mfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 アリシゼーション指摘の回帰試練（ALIC-1・ALIC-4）。
 *
 * <h2>ALIC-1（P1）: TOTP の再セットアップが 500</h2>
 * <p>{@code setupTotp} が未有効の既存行を {@code delete} してから新エンティティを {@code save} していたため、
 * flush 順序（INSERT が DELETE より先）で {@code uq_two_factor_auth_user} の一意制約違反となり、
 * 「設定開始 → 確定せず放棄 → 再度設定開始」の 2 回目以降が常に 500 になっていた。</p>
 *
 * <h2>ALIC-4（P3）: 回復要求の無効セッションが AUTH_019</h2>
 * <p>不正・期限切れの MFA セッションで {@code /2fa/recovery/request} を叩くと「2段階認証がまだ有効化されていません」
 * （AUTH_019）が返っていた。同じ条件の {@code /2fa/validate} は AUTH_073（再ログイン要）であり、揃える。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 ALIC-1 TOTP再セットアップ・ALIC-4 回復要求の無効セッション")
class TotpResetupAndRecoverySessionIT extends MfaDisableRecoveryItSupport {

    @Nested
    @DisplayName("ALIC-1 TOTP 設定開始の再実行")
    class Resetup {

        /** 設定開始 → 放棄 → 設定開始が 201 で、2FA 行は 1 行のまま。最後に払い出した秘密鍵で確定できる。 */
        @Test
        @DisplayName("設定開始を放棄して再実行しても201で行は1行・最新の秘密鍵で確定できる")
        void 放棄後の再設定開始は201で行は1行() throws Exception {
            long userId = createPlainUser();

            String first = setupTotpViaApi(userId);
            MvcResult second = setup(userId);
            assertThat(second.getResponse().getStatus()).as("ALIC-1 2 回目の設定開始: %s", body(second))
                    .isEqualTo(201);
            MvcResult third = setup(userId);
            assertThat(third.getResponse().getStatus()).as("ALIC-1 3 回目の設定開始: %s", body(third))
                    .isEqualTo(201);
            String latest = json(third).path("data").path("secret").asText(null);

            assertThat(rowCount(userId)).as("ALIC-1 2FA 行は 1 行のまま").isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT is_enabled FROM two_factor_auth WHERE user_id = ?", Boolean.class, userId))
                    .as("ALIC-1 未有効のまま").isFalse();
            assertThat(latest).as("秘密鍵は差し替わる").isNotEqualTo(first);

            MvcResult staleVerify = verifyTotpViaApi(userId, currentTotp(first));
            assertThat(staleVerify.getResponse().getStatus()).as("放棄した秘密鍵では確定できない: %s", body(staleVerify))
                    .isEqualTo(400);

            MvcResult v = verifyTotpViaApi(userId, currentTotp(latest));
            assertThat(v.getResponse().getStatus()).as("最新の秘密鍵で確定できる: %s", body(v)).isEqualTo(200);
            assertThat(twoFactorEnabled(userId)).isTrue();
            assertThat(rowCount(userId)).as("確定後も 1 行").isEqualTo(1);
        }

        /** 実機の再現手順: 2FA 無効化 → 設定開始（成功）→ 放棄 → 再度設定開始が 201。 */
        @Test
        @DisplayName("2FA無効化後に設定開始を放棄して再実行しても201")
        void 無効化後の放棄再設定開始は201() throws Exception {
            MfaUser u = createTotpUser();
            MvcResult d = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));
            assertThat(d.getResponse().getStatus()).as("前提: 無効化: %s", body(d)).isEqualTo(200);

            setupTotpViaApi(u.userId());
            MvcResult again = setup(u.userId());

            assertThat(again.getResponse().getStatus()).as("ALIC-1 再設定開始: %s", body(again)).isEqualTo(201);
            assertThat(rowCount(u.userId())).as("ALIC-1 2FA 行は 1 行のまま").isEqualTo(1);
        }

        /** 有効化済みの設定開始は従来どおり AUTH_017 で拒否し、行を書き換えない。 */
        @Test
        @DisplayName("有効化済みの設定開始はAUTH_017で秘密鍵は不変")
        void 有効化済みの設定開始はAUTH_017() throws Exception {
            MfaUser u = createTotpUser();
            String before = jdbcTemplate.queryForObject(
                    "SELECT totp_secret FROM two_factor_auth WHERE user_id = ?", String.class, u.userId());

            MvcResult r = setup(u.userId());

            assertThat(errorCode(r)).as("有効化済み: %s", body(r)).isEqualTo("AUTH_017");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT totp_secret FROM two_factor_auth WHERE user_id = ?", String.class, u.userId()))
                    .as("秘密鍵は差し替わらない").isEqualTo(before);
            assertThat(twoFactorEnabled(u.userId())).isTrue();
        }
    }

    @Nested
    @DisplayName("ALIC-4 回復要求の無効セッションは AUTH_073")
    class RecoveryInvalidSession {

        @Test
        @DisplayName("不正なMFAセッションの回復要求は400 AUTH_073")
        void 不正セッションはAUTH_073() throws Exception {
            MvcResult r = requestRecovery("no-such-mfa-session-token");

            assertThat(r.getResponse().getStatus()).as("ALIC-4: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).as("ALIC-4 validate と同じ再ログイン要").isEqualTo("AUTH_073");
        }

        @Test
        @DisplayName("期限切れ（失効済み）のMFAセッションの回復要求は400 AUTH_073")
        void 期限切れセッションはAUTH_073() throws Exception {
            MfaUser u = createTotpUser();
            String session = loginForMfaSession(u);
            valkey.advance(Duration.ofMinutes(15).plusSeconds(1));

            MvcResult r = requestRecovery(session);

            assertThat(r.getResponse().getStatus()).as("ALIC-4: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).as("ALIC-4 不在と期限切れは同一応答").isEqualTo("AUTH_073");
        }

        /**
         * 有効なセッションだが 2FA がその後に無効化された場合も、validate と同じく AUTH_073（このセッションでは
         * 回復できない＝再ログイン要）。「セッション無効」と「2FA 無効」を応答で区別させない。
         */
        @Test
        @DisplayName("ログイン後に2FAが無効化されたセッションの回復要求も400 AUTH_073")
        void 二FA無効化後のセッションもAUTH_073() throws Exception {
            MfaUser u = createTotpUser();
            String session = loginForMfaSession(u);
            MvcResult d = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));
            assertThat(d.getResponse().getStatus()).as("前提: 無効化: %s", body(d)).isEqualTo(200);

            MvcResult r = requestRecovery(session);

            assertThat(r.getResponse().getStatus()).as("ALIC-4: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_073");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mfa_recovery_tokens WHERE user_id = ?", Integer.class, u.userId()))
                    .as("回復トークンは発行しない").isZero();
        }
    }

    private MvcResult setup(long userId) throws Exception {
        return mockMvc.perform(post(SETUP_PATH).servletPath(SETUP_PATH)
                        .with(user(String.valueOf(userId)))
                        .header("User-Agent", USER_AGENT))
                .andReturn();
    }

    private int rowCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM two_factor_auth WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
