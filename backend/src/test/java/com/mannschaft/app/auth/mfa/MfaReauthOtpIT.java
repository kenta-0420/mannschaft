package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: 無効化・バックアップコード再生成用の本人確認メールOTP（{@code POST /2fa/reauth/email/send}。AC-51）。
 *
 * <p>契約: 認証済みのみ・送信先は認証ユーザー本人の登録アドレスに固定（本文で宛先・対象を指定させない）・
 * 用途別名前空間（REAUTH。LOGIN/SETUP との相互流用不可は {@code MfaEmailOtpValidateIT} の AC-23 が見る）・
 * 試行上限（誤り 5 回で失効）・再送待ち 60 秒・acting-as 拒否。</p>
 *
 * <p>本人確認コードの「消費側」（{@code /2fa/disable}・{@code /2fa/backup-codes/regenerate}）の網羅は足軽3（AC-30〜32/44）の
 * 担当。ここでは REAUTH コードが実際に本人確認として通ること・失効することだけを確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 本人確認用メールOTP 送信（AC-51）")
class MfaReauthOtpIT extends MfaEmailOtpItSupport {

    /** AC-51: 認証済み本人の登録アドレスへ送り、応答はマスク済みアドレス・600 秒・60 秒。 */
    @Test
    @DisplayName("AC-51 本人の登録アドレスへ送り応答はマスク済み・600秒・60秒")
    void 本人宛に送る() throws Exception {
        MfaUser u = totpUser();

        MvcResult r = sendReauthOtp(u.userId());

        assertThat(r.getResponse().getStatus()).as("AC-51: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("maskedEmail").asText(null)).isNotBlank().isNotEqualTo(u.email());
        assertThat(data.path("expiresInSeconds").asLong(-1)).isEqualTo(600);
        assertThat(data.path("resendAfterSeconds").asLong(-1)).isEqualTo(60);
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(u.userId(), 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toAddress()).as("AC-51 宛先は本人").isEqualTo(u.email());
        assertThat(codeOf(rows.get(0))).matches("\\d{6}");
    }

    /**
     * AC-51: 本人固定。本文に他人の userId・任意の宛先を混ぜても、送信先は認証ユーザー本人のみ
     * （他人にも任意アドレスにも送らない）。
     */
    @Test
    @DisplayName("AC-51 本文で他人や任意アドレスを指定しても本人にしか送らない")
    void 本文で宛先を指定させない() throws Exception {
        MfaUser me = totpUser();
        MfaUser victim = totpUser();
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", victim.userId(), "email", "attacker-" + me.userId() + "@example.com"));

        MvcResult r = mockMvc.perform(withIp(post(REAUTH_SEND_PATH).servletPath(REAUTH_SEND_PATH), ip)
                        .with(user(String.valueOf(me.userId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as("AC-51: %s", body(r)).isEqualTo(200);
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(me.userId(), 1);
        assertThat(rows).extracting(OtpOutboxRow::toAddress).as("AC-51 本人宛のみ").containsOnly(me.email());
        Thread.sleep(300);
        assertThat(otpOutboxCount(victim.userId())).as("AC-51 他人に送らない").isZero();
        Integer toAttacker = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE to_address_hash = UNHEX(?)", Integer.class,
                encryptionService.hmac("attacker-" + me.userId() + "@example.com"));
        assertThat(toAttacker).as("AC-51 任意アドレスに送らない").isZero();
    }

    /** AC-51: 未認証は 401 で送らない。 */
    @Test
    @DisplayName("AC-51 未認証は401")
    void 未認証は401() throws Exception {
        MvcResult r = mockMvc.perform(post(REAUTH_SEND_PATH).servletPath(REAUTH_SEND_PATH)).andReturn();

        assertThat(r.getResponse().getStatus()).as("AC-51 未認証").isEqualTo(401);
    }

    /** AC-51: acting-as 中は 403 で、子にも保護者にも送らない。 */
    @Test
    @DisplayName("AC-51 acting-as中は403で送らない")
    void actingAsは403() throws Exception {
        long[] pair = createGuardianAndChild();

        MvcResult r = mockMvc.perform(withIp(post(REAUTH_SEND_PATH).servletPath(REAUTH_SEND_PATH), ip)
                        .with(user(String.valueOf(pair[0])))
                        .header(HEADER_PROXY_FOR, String.valueOf(pair[1])))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as("AC-51 acting-as: %s", body(r)).isEqualTo(403);
        Thread.sleep(300);
        assertThat(otpOutboxCount(pair[0])).isZero();
        assertThat(otpOutboxCount(pair[1])).isZero();
    }

    /** AC-51: 2FA 未有効のユーザーは本人確認 OTP を要しない（無効化・再生成の前提違反）ため 409 AUTH_079 で送らない。 */
    @Test
    @DisplayName("AC-51 2FA未有効は409 AUTH_079で送らない")
    void 未有効は409() throws Exception {
        long userId = plainUser();

        MvcResult r = sendReauthOtp(userId);

        assertThat(r.getResponse().getStatus()).as("AC-51: %s", body(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("AUTH_079");
        Thread.sleep(300);
        assertThat(otpOutboxCount(userId)).isZero();
    }

    /** AC-51: メールなし（内部プレースホルダ）のユーザーは 400 AUTH_081 で送らない。 */
    @Test
    @DisplayName("AC-51 メールなしは400 AUTH_081")
    void メールなしは400() throws Exception {
        MfaUser u = totpUser();
        updateEmail(u.userId(), placeholderEmail());

        MvcResult r = sendReauthOtp(u.userId());

        assertThat(r.getResponse().getStatus()).as("AC-51: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_081");
    }

    /** AC-51 境界: 再送は 59 秒後なら 429 AUTH_076（Retry-After 付き）、60 秒後なら送れる。 */
    @Test
    @DisplayName("AC-51 境界 再送は59秒429・60秒で送れる")
    void 再送待ち() throws Exception {
        MfaUser u = totpUser();
        sendReauthAndGetCode(u.userId());

        valkey.advance(Duration.ofSeconds(59));
        MvcResult early = sendReauthOtp(u.userId());
        valkey.advance(Duration.ofSeconds(1));
        MvcResult onTime = sendReauthOtp(u.userId());

        assertThat(early.getResponse().getStatus()).as("AC-51 59 秒: %s", body(early)).isEqualTo(429);
        assertThat(errorCode(early)).isEqualTo("AUTH_076");
        assertThat(retryAfter(early)).isNotNull();
        assertThat(onTime.getResponse().getStatus()).as("AC-51 60 秒: %s", body(onTime)).isEqualTo(200);
    }

    /** AC-51: 本人確認コードは無効化の本人確認として通る（PW＋EMAIL 要素で 2FA 行が消える）。 */
    @Test
    @DisplayName("AC-51 本人確認コードで無効化の本人確認が通る")
    void 本人確認コードで無効化できる() throws Exception {
        MfaUser u = totpUser();
        String code = sendReauthAndGetCode(u.userId());

        MvcResult r = disable(u.userId(), PASSWORD, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-51: %s", body(r)).isEqualTo(200);
        assertThat(twoFactorRow(u.userId())).as("AC-51 無効化された").isNull();
    }

    /** AC-51: 本人確認コードは 1 回限り（再生成で使ったコードを無効化に再使用できない）。 */
    @Test
    @DisplayName("AC-51 本人確認コードは1回限り")
    void 本人確認コードは一回限り() throws Exception {
        MfaUser u = totpUser();
        String code = sendReauthAndGetCode(u.userId());
        MvcResult regen = regenerate(u.userId(), "EMAIL", code);
        assertThat(regen.getResponse().getStatus()).as("前提: 再生成で本人確認が通る: %s", body(regen))
                .isEqualTo(200);

        MvcResult reuse = disable(u.userId(), PASSWORD, "EMAIL", code);

        assertThat(reuse.getResponse().getStatus()).as("AC-51 再使用: %s", body(reuse)).isEqualTo(400);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_080");
        assertThat(twoFactorRow(u.userId())).as("AC-51 2FA は有効のまま").isNotNull();
    }

    /**
     * AC-51 試行上限: 本人確認コードを 5 回誤ると失効し、正しいコードでも本人確認に通らない。
     * 誤りは再生成 EP で、失効の確認は無効化 EP で行う（EP 個別のレート制限ではなく、
     * REAUTH コード自体の試行上限で止まることを確かめるため）。
     */
    @Test
    @DisplayName("AC-51 誤り5回で本人確認コードは失効し正しいコードでも通らない")
    void 誤り五回で失効() throws Exception {
        MfaUser u = totpUser();
        String code = sendReauthAndGetCode(u.userId());
        String before = backupCodesJson(u.userId());
        for (int i = 0; i < 5; i++) {
            MvcResult wrong = regenerate(u.userId(), "EMAIL", otherCode(code));
            assertThat(wrong.getResponse().getStatus()).as("AC-51 誤り %d 回目は失敗: %s", i + 1, body(wrong))
                    .isIn(400, 429);
        }

        MvcResult after = disable(u.userId(), PASSWORD, "EMAIL", code);

        assertThat(after.getResponse().getStatus()).as("AC-51 失効後の正コード: %s", body(after)).isEqualTo(400);
        assertThat(errorCode(after)).isEqualTo("AUTH_080");
        assertThat(twoFactorRow(u.userId())).as("AC-51 2FA は有効のまま").isNotNull();
        assertThat(backupCodesJson(u.userId())).as("AC-51 バックアップコード不変").isEqualTo(before);
    }

    /** AC-51 境界: 誤り 4 回の後でも正しい本人確認コードは通る。 */
    @Test
    @DisplayName("AC-51 境界 誤り4回後も正しいコードは通る")
    void 誤り四回後も通る() throws Exception {
        MfaUser u = totpUser();
        String code = sendReauthAndGetCode(u.userId());
        for (int i = 0; i < 4; i++) {
            regenerate(u.userId(), "EMAIL", otherCode(code));
        }

        MvcResult ok = disable(u.userId(), PASSWORD, "EMAIL", code);

        assertThat(ok.getResponse().getStatus()).as("AC-51 4 回後: %s", body(ok)).isEqualTo(200);
    }

    /** AC-51: 本人確認コードは 10 分で失効する。 */
    @Test
    @DisplayName("AC-51 本人確認コードは10分で失効")
    void 十分で失効() throws Exception {
        MfaUser u = totpUser();
        String code = sendReauthAndGetCode(u.userId());
        valkey.advance(OTP_TTL);

        MvcResult r = disable(u.userId(), PASSWORD, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-51 期限切れ: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_080");
        assertThat(twoFactorRow(u.userId())).isNotNull();
    }
}
