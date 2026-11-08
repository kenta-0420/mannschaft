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
 * CMP-261012-0910 自己スコープ契約テスト: 2FA の本人専用エンドポイントは、無関係な他ユーザーの 2FA 設定・
 * メールOTP・通知に到達できない（{@code @SelfScopedEndpoint} の宣言を実際に叩いて固定する）。
 *
 * <p>対象（{@code SelfScopedEndpointMarkerGuardTest} のトレーサビリティ・リンク）:</p>
 * <ul>
 *   <li>{@code Auth2faController#getTwoFactorStatus} — 状態取得は呼び出し者本人の 2FA だけを返す</li>
 *   <li>{@code Auth2faController#disableTwoFactor} — 他人の要素を提示しても他人の 2FA は外れない</li>
 *   <li>{@code Auth2faController#startEmailSetup} — 設定用コードは呼び出し者本人の登録アドレスにだけ届く</li>
 *   <li>{@code Auth2faController#enableEmailTwoFactor} — 他人の設定用コードでは有効化できず、他人の設定も消費しない</li>
 *   <li>{@code Auth2faController#sendReauthEmailOtp} — 本人確認用コードは呼び出し者本人の登録アドレスにだけ届く</li>
 * </ul>
 *
 * <p>いずれも対象ユーザーを {@code SecurityUtils.getCurrentUserId()} だけで解決し、本文・クエリに他人の ID を
 * 受ける口を持たない。本テストは「攻撃者（認証済みの別ユーザー）が叩いても被害者の行・Valkey キー・outbox に
 * 何も起きない」ことを、被害者側の状態を直接観測して固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA 本人専用エンドポイントの自己スコープ契約")
class Auth2faSelfScopeContractIT extends MfaEmailOtpItSupport {

    private static final String STATUS_PATH = "/api/v1/auth/2fa/status";

    private MvcResult status(long userId, String queryUserId) throws Exception {
        var req = get(STATUS_PATH).servletPath(STATUS_PATH).with(user(String.valueOf(userId)));
        if (queryUserId != null) {
            req = req.param("userId", queryUserId);
        }
        return mockMvc.perform(req).andReturn();
    }

    private boolean twoFactorEnabled(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM two_factor_auth WHERE user_id = ? AND is_enabled = 1", Integer.class, userId);
        return n != null && n > 0;
    }

    private String emailOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    @DisplayName("Auth2faController#getTwoFactorStatus は呼び出し者本人の状態だけを返し、他人のIDを渡しても他人の状態は見えない")
    void 状態取得は本人の状態だけを返す() throws Exception {
        MfaUser victim = totpUser();
        long attacker = plainUser();

        MvcResult own = status(attacker, null);
        MvcResult probe = status(attacker, String.valueOf(victim.userId()));

        assertThat(own.getResponse().getStatus()).as("本人の状態取得: %s", body(own)).isEqualTo(200);
        assertThat(json(own).path("data").path("enabled").asBoolean(true)).as("攻撃者自身は未設定").isFalse();
        assertThat(probe.getResponse().getStatus()).as("他人の ID を渡しても: %s", body(probe)).isEqualTo(200);
        JsonNode probed = json(probe).path("data");
        assertThat(probed.path("enabled").asBoolean(true)).as("他人（有効化済み）の状態は返らない").isFalse();
        assertThat(probed.path("backupCodesRemaining").asInt(-1)).as("他人のバックアップコード残数は返らない").isZero();
        MvcResult victimOwn = status(victim.userId(), null);
        assertThat(json(victimOwn).path("data").path("enabled").asBoolean(false)).as("前提: 被害者は有効化済み").isTrue();
    }

    @Test
    @DisplayName("Auth2faController#disableTwoFactor は他人の確認コードを提示しても他人の2FAを外せない")
    void 無効化は他人の2FAを外せない() throws Exception {
        MfaUser victim = totpUser();
        MfaUser attacker = totpUser();

        MvcResult r = disable(attacker.userId(), PASSWORD, "TOTP", currentTotp(victim.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("他人の TOTP では本人確認に失敗する: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_080");
        assertThat(twoFactorEnabled(victim.userId())).as("被害者の 2FA は有効のまま").isTrue();
        assertThat(twoFactorEnabled(attacker.userId())).as("攻撃者自身の 2FA も外れない").isTrue();
    }

    @Test
    @DisplayName("Auth2faController#startEmailSetup の設定用コードは呼び出し者本人の登録アドレスにだけ届く")
    void メール2FA設定開始は本人宛にだけ送る() throws Exception {
        long victim = plainUser();
        long attacker = plainUser();

        MvcResult r = startEmailSetup(attacker);

        assertThat(r.getResponse().getStatus()).as("前提: 設定開始: %s", body(r)).isEqualTo(200);
        assertThat(awaitOtpOutboxRows(attacker, 1)).extracting(OtpOutboxRow::toAddress)
                .as("宛先は攻撃者本人の登録アドレスだけ").containsOnly(emailOf(attacker));
        assertThat(otpOutboxCount(victim)).as("被害者宛には何も送られない").isZero();
        assertThat(valkey.liveKeysMatching(SETUP_OTP_KEY_MARK + ".*" + victim + "$"))
                .as("被害者の設定用コードは作られない").isEmpty();
    }

    @Test
    @DisplayName("Auth2faController#enableEmailTwoFactor は他人の設定用コードで有効化できず、他人の設定用コードも消費しない")
    void メール2FA有効化は他人の設定用コードを使えない() throws Exception {
        long victim = plainUser();
        long attacker = plainUser();
        String victimCode = startSetupAndGetCode(victim);

        MvcResult stolen = enableEmail(attacker, victimCode);

        assertThat(stolen.getResponse().getStatus()).as("他人のコードでは有効化できない: %s", body(stolen))
                .isNotEqualTo(200);
        assertThat(twoFactorEnabled(attacker)).as("攻撃者は有効化されない").isFalse();
        assertThat(twoFactorEnabled(victim)).as("被害者も勝手に有効化されない").isFalse();

        MvcResult own = enableEmail(victim, victimCode);
        assertThat(own.getResponse().getStatus()).as("被害者のコードは消費されておらず本人は有効化できる: %s", body(own))
                .isEqualTo(200);
        assertThat(twoFactorEnabled(victim)).isTrue();
    }

    @Test
    @DisplayName("Auth2faController#sendReauthEmailOtp の本人確認用コードは呼び出し者本人の登録アドレスにだけ届く")
    void 本人確認用コードは本人宛にだけ送る() throws Exception {
        MfaUser victim = totpUser();
        MfaUser attacker = totpUser();

        MvcResult r = sendReauthOtp(attacker.userId());

        assertThat(r.getResponse().getStatus()).as("前提: 本人確認用コード送信: %s", body(r)).isEqualTo(200);
        assertThat(awaitOtpOutboxRows(attacker.userId(), 1)).extracting(OtpOutboxRow::toAddress)
                .as("宛先は攻撃者本人の登録アドレスだけ").containsOnly(attacker.email());
        assertThat(otpOutboxCount(victim.userId())).as("被害者宛には何も送られない").isZero();
    }
}
