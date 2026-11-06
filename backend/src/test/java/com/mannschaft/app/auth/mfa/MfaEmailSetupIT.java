package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: メール 2FA の設定（{@code POST /2fa/email/setup} → {@code POST /2fa/email/enable}）。
 *
 * <p>対象 AC: AC-25（設定開始・有効化・バックアップコード 8 件・MfaEnabledEvent(EMAIL)・有効化済み 409・
 * 60 秒待ち・acting-as 拒否）、AC-45（不正コード上限 5 回で失効・再送/setup 再実行で回避不可・成功コード再利用不可）。</p>
 *
 * <p>acting-as は実フィルタ（{@code ProxyInputContextFilter} の後見切替経路）を通すため、保護者リンク APPROVED・
 * 8 歳の子を実 DB に作り {@code X-Proxy-For-User-Id} を付けて叩く。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 メール2FA 設定・有効化（AC-25/45）")
class MfaEmailSetupIT extends MfaEmailOtpItSupport {

    @Autowired
    private ApplicationEvents events;

    // ═════════════════════════════════════════════════════════════
    // AC-25 設定開始・有効化
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-25: 設定開始は本人の登録アドレスへ設定用コードを送り（outbox 1 件・応答 maskedEmail/600/60）、
     * Valkey には設定用名前空間のキーを TTL 10 分で置く（平文コードは置かない）。
     */
    @Test
    @DisplayName("AC-25 設定開始で本人宛に設定用コードを送り応答はマスク済み・600秒・60秒")
    void 設定開始() throws Exception {
        long userId = plainUser();
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);

        MvcResult r = startEmailSetup(userId);

        assertThat(r.getResponse().getStatus()).as("AC-25: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("maskedEmail").asText(null)).as("AC-25 maskedEmail").isNotBlank().isNotEqualTo(email);
        assertThat(data.path("expiresInSeconds").asLong(-1)).isEqualTo(600);
        assertThat(data.path("resendAfterSeconds").asLong(-1)).isEqualTo(60);
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(userId, 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toAddress()).as("AC-25 宛先は本人").isEqualTo(email);
        String code = codeOf(rows.get(0));
        Set<String> keys = valkey.liveKeysMatching(SETUP_OTP_KEY_MARK);
        assertThat(keys).as("AC-25 設定用 OTP キー（%s）", SETUP_OTP_KEY_MARK).hasSize(1);
        String key = keys.iterator().next();
        assertThat(valkey.rawGet(key)).as("AC-25 平文コードを置かない").doesNotContain(code);
        assertThat(valkey.ttlMillis(key)).as("AC-25 TTL 10 分")
                .isBetween(OTP_TTL.minusSeconds(10).toMillis(), OTP_TTL.toMillis());
        assertThat(notEnabled(userId)).as("AC-25 設定開始だけでは 2FA 行を有効化しない").isTrue();
    }

    /**
     * AC-25: 正しい設定用コードで method=EMAIL・is_enabled・totp_secret NULL・バックアップコード 8 件で有効化し、
     * 応答で平文バックアップコード 8 件（8 桁・重複なし）を返す。MfaEnabledEvent(method=EMAIL) を発行する。
     */
    @Test
    @DisplayName("AC-25 正しいコードでEMAIL方式有効化・バックアップコード8件・MfaEnabledEvent(EMAIL)")
    void 有効化できる() throws Exception {
        long userId = plainUser();
        String code = startSetupAndGetCode(userId);

        MvcResult r = enableEmail(userId, code);

        assertThat(r.getResponse().getStatus()).as("AC-25: %s", body(r)).isEqualTo(200);
        List<String> backup = texts(json(r).path("data").path("backupCodes"));
        assertThat(backup).as("AC-25 バックアップコード 8 件").hasSize(8)
                .allSatisfy(c -> assertThat(c).matches("\\d{8}"));
        assertThat(new HashSet<>(backup)).as("AC-25 重複なし").hasSize(8);
        Map<String, Object> row = twoFactorRow(userId);
        assertThat(row).as("AC-25 two_factor_auth 行").isNotNull();
        assertThat(row.get("method")).as("AC-25 method").isEqualTo("EMAIL");
        assertThat(enabled(row)).as("AC-25 is_enabled").isTrue();
        assertThat(row.get("totp_secret")).as("AC-25 totp_secret は NULL").isNull();
        assertThat(((Number) row.get("backup_count")).intValue()).as("AC-25 保存は 8 件").isEqualTo(8);
        assertThat(backupCodesJson(userId)).as("AC-25 平文で保存しない")
                .doesNotContain(backup.get(0)).doesNotContain(backup.get(7));
        List<MfaEnabledEvent> enabled = events.stream(MfaEnabledEvent.class).toList();
        assertThat(enabled).as("AC-25 MfaEnabledEvent").hasSize(1);
        assertThat(enabled.get(0).getUserId()).isEqualTo(userId);
        assertThat(enabled.get(0).getMethod()).as("AC-25 method=EMAIL").isEqualTo(TwoFactorMethod.EMAIL);
    }

    /** AC-25: 有効化後のログインは MFA を要求し、主手段は EMAIL。発行されたバックアップコードでログインできる。 */
    @Test
    @DisplayName("AC-25 有効化後のログインは主手段EMAILでバックアップコードも使える")
    void 有効化後のログイン() throws Exception {
        long userId = plainUser();
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        MvcResult en = enableEmail(userId, startSetupAndGetCode(userId));
        List<String> backup = texts(json(en).path("data").path("backupCodes"));
        assertThat(backup).as("前提: 有効化成功: %s", body(en)).hasSize(8);

        MvcResult login = login(email);
        JsonNode data = json(login).path("data");

        assertThat(data.path("mfaRequired").asBoolean()).as("AC-25 MFA 要求").isTrue();
        assertThat(data.path("primaryMethod").asText(null)).as("AC-25 主手段").isEqualTo("EMAIL");
        MvcResult ok = validate(data.path("mfaSessionToken").asText(), "BACKUP_CODE", backup.get(0));
        assertThat(ok.getResponse().getStatus()).as("AC-25 バックアップコードでログイン: %s", body(ok)).isEqualTo(200);
    }

    /**
     * AC-25: TOTP 設定途中（未有効・秘密鍵あり）の行が残っていても、メール有効化は method=EMAIL で上書きし、
     * TOTP 秘密鍵を残さない（方式の混在を作らない）。
     */
    @Test
    @DisplayName("AC-25 TOTP設定途中の未有効行があってもEMAIL有効化で秘密鍵を残さない")
    void TOTP設定途中の行を上書き() throws Exception {
        long userId = plainUser();
        twoFactorAuthRepository.save(TwoFactorAuthEntity.builder()
                .userId(userId)
                .method(TwoFactorMethod.TOTP)
                .totpSecret(encryptionService.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"))
                .isEnabled(false)
                .build());
        String code = startSetupAndGetCode(userId);

        MvcResult r = enableEmail(userId, code);

        assertThat(r.getResponse().getStatus()).as("AC-25: %s", body(r)).isEqualTo(200);
        Map<String, Object> row = twoFactorRow(userId);
        assertThat(row.get("method")).isEqualTo("EMAIL");
        assertThat(enabled(row)).isTrue();
        assertThat(row.get("totp_secret")).as("AC-25 TOTP 秘密鍵を残さない").isNull();
    }

    /** AC-25: 2FA 有効化済み（TOTP）のユーザーは設定開始も有効化も 409 AUTH_078。送信もしない。 */
    @Test
    @DisplayName("AC-25 有効化済みは設定開始・有効化とも409 AUTH_078")
    void 有効化済みは409() throws Exception {
        MfaUser u = totpUser();
        String before = backupCodesJson(u.userId());

        MvcResult setup = startEmailSetup(u.userId());
        MvcResult enable = enableEmail(u.userId(), "123456");

        assertThat(setup.getResponse().getStatus()).as("AC-25 setup: %s", body(setup)).isEqualTo(409);
        assertThat(errorCode(setup)).isEqualTo("AUTH_078");
        assertThat(enable.getResponse().getStatus()).as("AC-25 enable: %s", body(enable)).isEqualTo(409);
        assertThat(errorCode(enable)).isEqualTo("AUTH_078");
        Thread.sleep(300);
        assertThat(otpOutboxCount(u.userId())).as("AC-25 有効化済みには送らない").isZero();
        Map<String, Object> row = twoFactorRow(u.userId());
        assertThat(row.get("method")).as("AC-25 方式不変").isEqualTo("TOTP");
        assertThat(backupCodesJson(u.userId())).as("AC-25 バックアップコード不変").isEqualTo(before);
        assertThat(events.stream(MfaEnabledEvent.class)).as("AC-25 イベントなし").isEmpty();
    }

    /** AC-25: EMAIL 方式で有効化済みのユーザーも設定開始は 409 AUTH_078。 */
    @Test
    @DisplayName("AC-25 EMAIL方式で有効化済みも設定開始は409")
    void EMAIL有効化済みも409() throws Exception {
        MfaUser u = emailUser();

        MvcResult setup = startEmailSetup(u.userId());

        assertThat(setup.getResponse().getStatus()).as("AC-25: %s", body(setup)).isEqualTo(409);
        assertThat(errorCode(setup)).isEqualTo("AUTH_078");
    }

    /** AC-25 境界: 設定開始の再実行は 59 秒後なら 429 AUTH_076（Retry-After 付き）、60 秒後なら送れる。 */
    @Test
    @DisplayName("AC-25 境界 設定開始の再実行は59秒429・60秒で送れる")
    void 設定開始の再送待ち() throws Exception {
        long userId = plainUser();
        startSetupAndGetCode(userId);

        valkey.advance(Duration.ofSeconds(59));
        MvcResult early = startEmailSetup(userId);
        valkey.advance(Duration.ofSeconds(1));
        MvcResult onTime = startEmailSetup(userId);

        assertThat(early.getResponse().getStatus()).as("AC-25 59 秒: %s", body(early)).isEqualTo(429);
        assertThat(errorCode(early)).isEqualTo("AUTH_076");
        assertThat(retryAfter(early)).as("AC-25 Retry-After").isNotNull();
        assertThat(onTime.getResponse().getStatus()).as("AC-25 60 秒: %s", body(onTime)).isEqualTo(200);
    }

    /** AC-25: 設定開始の再実行で旧コードは無効になり、新コードだけで有効化できる。 */
    @Test
    @DisplayName("AC-25 設定開始の再実行で旧コードは無効")
    void 設定開始再実行で旧コード無効() throws Exception {
        long userId = plainUser();
        String first = startSetupAndGetCode(userId);
        valkey.advance(RESEND_WAIT);
        String second = startSetupAndGetCode(userId);
        org.assertj.core.api.Assumptions.assumeThat(second).isNotEqualTo(first);

        MvcResult old = enableEmail(userId, first);
        MvcResult fresh = enableEmail(userId, second);

        assertThat(old.getResponse().getStatus()).as("AC-25 旧コード: %s", body(old)).isEqualTo(400);
        assertThat(errorCode(old)).isEqualTo("AUTH_074");
        assertThat(fresh.getResponse().getStatus()).as("AC-25 新コード: %s", body(fresh)).isEqualTo(200);
    }

    /** AC-25: 誤ったコードは 400 AUTH_074 で有効化しない。設定開始前の有効化も AUTH_074。 */
    @Test
    @DisplayName("AC-25 誤コード・未開始は400 AUTH_074で有効化しない")
    void 誤コードは有効化しない() throws Exception {
        long notStarted = plainUser();
        long userId = plainUser();
        String code = startSetupAndGetCode(userId);

        MvcResult wrong = enableEmail(userId, otherCode(code));
        MvcResult none = enableEmail(notStarted, "123456");

        assertThat(wrong.getResponse().getStatus()).as("AC-25 誤コード: %s", body(wrong)).isEqualTo(400);
        assertThat(errorCode(wrong)).isEqualTo("AUTH_074");
        assertThat(none.getResponse().getStatus()).as("AC-25 未開始: %s", body(none)).isEqualTo(400);
        assertThat(errorCode(none)).isEqualTo("AUTH_074");
        assertThat(notEnabled(userId)).as("AC-25 有効化しない").isTrue();
        assertThat(twoFactorRow(notStarted)).isNull();
    }

    /** AC-25: 形式違反（5 桁・英字・空）は 400 で有効化しない。 */
    @Test
    @DisplayName("AC-25 形式違反コードは400")
    void 形式違反は400() throws Exception {
        long userId = plainUser();
        startSetupAndGetCode(userId);

        MvcResult five = enableEmail(userId, "12345");
        MvcResult alpha = enableEmail(userId, "12a456");
        MvcResult blank = enableEmail(userId, "");

        assertThat(five.getResponse().getStatus()).as("AC-25 5 桁: %s", body(five)).isEqualTo(400);
        assertThat(alpha.getResponse().getStatus()).as("AC-25 英字: %s", body(alpha)).isEqualTo(400);
        assertThat(blank.getResponse().getStatus()).as("AC-25 空: %s", body(blank)).isEqualTo(400);
        assertThat(notEnabled(userId)).isTrue();
    }

    /** AC-25: 未認証は設定開始・有効化とも 401。 */
    @Test
    @DisplayName("AC-25 未認証は401")
    void 未認証は401() throws Exception {
        MvcResult setup = mockMvc.perform(post(EMAIL_SETUP_PATH).servletPath(EMAIL_SETUP_PATH)).andReturn();
        MvcResult enable = mockMvc.perform(post(EMAIL_ENABLE_PATH).servletPath(EMAIL_ENABLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andReturn();

        assertThat(setup.getResponse().getStatus()).as("AC-25 setup").isEqualTo(401);
        assertThat(enable.getResponse().getStatus()).as("AC-25 enable").isEqualTo(401);
    }

    /** AC-25: 後見切替（acting-as）中は設定開始・有効化とも 403 で、子にも保護者にも送信・有効化しない。 */
    @Test
    @DisplayName("AC-25 acting-as中は設定開始・有効化とも403")
    void actingAsは403() throws Exception {
        long[] pair = createGuardianAndChild();
        long guardian = pair[0];
        long child = pair[1];

        MvcResult setup = mockMvc.perform(withIp(post(EMAIL_SETUP_PATH).servletPath(EMAIL_SETUP_PATH), ip)
                        .with(user(String.valueOf(guardian)))
                        .header(HEADER_PROXY_FOR, String.valueOf(child)))
                .andReturn();
        MvcResult enable = mockMvc.perform(withIp(post(EMAIL_ENABLE_PATH).servletPath(EMAIL_ENABLE_PATH), ip)
                        .with(user(String.valueOf(guardian)))
                        .header(HEADER_PROXY_FOR, String.valueOf(child))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andReturn();

        assertThat(setup.getResponse().getStatus()).as("AC-25 acting-as setup: %s", body(setup)).isEqualTo(403);
        assertThat(enable.getResponse().getStatus()).as("AC-25 acting-as enable: %s", body(enable)).isEqualTo(403);
        Thread.sleep(300);
        assertThat(otpOutboxCount(child)).as("AC-25 子へ送らない").isZero();
        assertThat(otpOutboxCount(guardian)).as("AC-25 保護者へ送らない").isZero();
        assertThat(twoFactorRow(child)).isNull();
        assertThat(twoFactorRow(guardian)).isNull();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-45 有効化の不正コード上限
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-45 境界: 不正コード 4 回の後でも正しいコードで有効化できる（5 回に達していない）。
     */
    @Test
    @DisplayName("AC-45 境界 不正4回後も正しいコードで有効化できる")
    void 不正四回後も有効化できる() throws Exception {
        long userId = plainUser();
        String code = startSetupAndGetCode(userId);
        for (int i = 0; i < 4; i++) {
            enableEmail(userId, otherCode(code));
        }

        MvcResult ok = enableEmail(userId, code);

        assertThat(ok.getResponse().getStatus()).as("AC-45 4 回後: %s", body(ok)).isEqualTo(200);
    }

    /**
     * AC-45 境界: 不正コード 5 回目で設定用コードは失効（429 AUTH_075）。以後は正しいコードでも有効化できない。
     */
    @Test
    @DisplayName("AC-45 境界 不正5回目で失効・以後は正しいコードでも不可")
    void 不正五回で失効() throws Exception {
        long userId = plainUser();
        String code = startSetupAndGetCode(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(errorCode(enableEmail(userId, otherCode(code))));
        }

        MvcResult fifth = enableEmail(userId, otherCode(code));
        MvcResult after = enableEmail(userId, code);

        assertThat(codes).as("AC-45 1〜4 回目").containsOnly("AUTH_074");
        assertThat(fifth.getResponse().getStatus()).as("AC-45 5 回目: %s", body(fifth)).isEqualTo(429);
        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(after.getResponse().getStatus()).as("AC-45 失効後の正コード: %s", body(after)).isNotEqualTo(200);
        assertThat(valkey.liveKeysMatching(SETUP_OTP_KEY_MARK)).as("AC-45 設定用コードは消える").isEmpty();
        assertThat(notEnabled(userId)).as("AC-45 有効化されない").isTrue();
    }

    /**
     * AC-45: 不正回数は設定開始の再実行（再送）でリセットされない。4 回誤った後に再送して新コードを得ても、
     * 次の 1 回の誤りで上限（429 AUTH_075）に達し、新コードでも有効化できない。
     */
    @Test
    @DisplayName("AC-45 不正回数は再送・setup再実行でリセットされない")
    void 再送で回数はリセットされない() throws Exception {
        long userId = plainUser();
        String first = startSetupAndGetCode(userId);
        for (int i = 0; i < 4; i++) {
            enableEmail(userId, otherCode(first));
        }
        valkey.advance(RESEND_WAIT);
        String second = startSetupAndGetCode(userId);

        MvcResult fifth = enableEmail(userId, otherCode(second));
        MvcResult after = enableEmail(userId, second);

        assertThat(fifth.getResponse().getStatus()).as("AC-45 再送後の 1 回目の誤り＝通算 5 回目: %s", body(fifth))
                .isEqualTo(429);
        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(after.getResponse().getStatus()).as("AC-45 新コードでも不可: %s", body(after)).isNotEqualTo(200);
        assertThat(notEnabled(userId)).isTrue();
    }

    /**
     * AC-45: 有効化に成功したコードは再利用できない（成功で設定用コードを消す）。2FA を外した後に同じコードで
     * 有効化しようとしても AUTH_074。
     */
    @Test
    @DisplayName("AC-45 成功したコードは再利用できない")
    void 成功コードは再利用不可() throws Exception {
        long userId = plainUser();
        String code = startSetupAndGetCode(userId);
        assertThat(enableEmail(userId, code).getResponse().getStatus()).as("前提: 有効化成功").isEqualTo(200);
        assertThat(valkey.liveKeysMatching(SETUP_OTP_KEY_MARK)).as("AC-45 成功で設定用コードを消す").isEmpty();
        jdbcTemplate.update("DELETE FROM two_factor_auth WHERE user_id = ?", userId);

        MvcResult reuse = enableEmail(userId, code);

        assertThat(reuse.getResponse().getStatus()).as("AC-45 再利用: %s", body(reuse)).isEqualTo(400);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_074");
        assertThat(twoFactorRow(userId)).isNull();
    }
}
