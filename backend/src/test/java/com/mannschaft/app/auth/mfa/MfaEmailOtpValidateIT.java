package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: メールOTP によるログイン検証（{@code POST /api/v1/auth/2fa/validate} method=EMAIL）。
 *
 * <p>対象 AC: AC-22（正・誤・期限切れ・未送信）、AC-23（セッション跨ぎ・用途 LOGIN/SETUP/REAUTH の相互流用不可）、
 * AC-24（EMAIL 方式の主手段・TOTP ユーザーの代替・メールなしユーザー）。</p>
 *
 * <h2>「メールなし」ユーザーのフィクスチャについて（AC-24）</h2>
 * <p>{@code users.email} は {@code NOT NULL UNIQUE}（V1.001。V9.053 でも変更なし）のため NULL は作れず、空文字は
 * UNIQUE 制約で 1 行しか置けないうえログイン（{@code @Email @NotBlank}）もできない。本アプリで「メールなし」は
 * 既存規約どおり内部プレースホルダ {@code *.mannschaft.internal}（後見で作られた子・退会匿名化等。
 * {@code GuardianshipHandoverService} 参照）で表現されるため、それをフィクスチャにする。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 メールOTP ログイン検証（AC-22/23/24）")
class MfaEmailOtpValidateIT extends MfaEmailOtpItSupport {

    @Autowired
    private ApplicationEvents events;

    // ═════════════════════════════════════════════════════════════
    // AC-22 正・誤・期限切れ・未送信
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-22: 正しいメールOTP でトークン発行（Cookie・応答 method=EMAIL・LoginSuccessEvent(method=EMAIL)）、
     * Valkey のコードは削除される。
     */
    @Test
    @DisplayName("AC-22 正しいコードでトークン発行・method=EMAIL・コード削除")
    void 正しいコードでログインできる() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);

        MvcResult r = validate(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-22: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("accessToken").asText(null)).as("AC-22 アクセストークン").isNotBlank();
        assertThat(data.path("method").asText(null)).as("AC-22 method").isEqualTo("EMAIL");
        assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .as("AC-22 Cookie 発行").anySatisfy(c -> assertThat(c).startsWith("access_token="));
        assertThat(valkey.liveKeysMatching(LOGIN_OTP_KEY_MARK)).as("AC-22 成功でコードを削除").isEmpty();
        List<LoginSuccessEvent> success = events.stream(LoginSuccessEvent.class).toList();
        assertThat(success).as("AC-22 LoginSuccessEvent").hasSize(1);
        assertThat(success.get(0).getMethod()).as("AC-22 LoginSuccessEvent.method").isEqualTo("EMAIL");
        assertThat(success.get(0).getUserAgent()).as("AC-22 UA").isEqualTo(USER_AGENT);
    }

    /** AC-22: 使ったコードは再利用できない（同じコードで別セッションを開いても通らない）。 */
    @Test
    @DisplayName("AC-22 成功したコードは別セッションで再利用できない")
    void 成功したコードは再利用不可() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        assertThat(validate(session, "EMAIL", code).getResponse().getStatus()).as("前提: 1 回目成功").isEqualTo(200);
        String session2 = loginForMfaSession(u);

        MvcResult reuse = validate(session2, "EMAIL", code);

        assertThat(reuse.getResponse().getStatus()).as("AC-22 再利用: %s", body(reuse)).isEqualTo(400);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_074");
    }

    /**
     * AC-22: 誤ったコードは AUTH_074 で、AC-14 の試行回数に計上される（誤り 4 回の後でも正しいコードは通るが、
     * 5 回目の誤りで 429 AUTH_075・セッション削除）。誤りでコード自体は失効しない（1 回の打ち間違いで再送を強いない）。
     */
    @Test
    @DisplayName("AC-22 誤りはAUTH_074で試行計上・4回後も正コードは通る")
    void 誤りは試行計上され四回後も通る() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(errorCode(validate(session, "EMAIL", otherCode(code))));
        }

        MvcResult ok = validate(session, "EMAIL", code);

        assertThat(codes).as("AC-22 誤りは AUTH_074").containsOnly("AUTH_074");
        assertThat(ok.getResponse().getStatus()).as("AC-22 4 回誤り後の正コード: %s", body(ok)).isEqualTo(200);
    }

    /** AC-22: メールOTP の誤り 5 回目で AC-14 の上限（429 AUTH_075）に達し、以後は正しいコードでも再ログイン要。 */
    @Test
    @DisplayName("AC-22 誤り5回目は429 AUTH_075・以後は正コードもAUTH_073")
    void 誤り五回でセッション削除() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        for (int i = 0; i < 4; i++) {
            validate(session, "EMAIL", otherCode(code));
        }

        MvcResult fifth = validate(session, "EMAIL", otherCode(code));
        MvcResult after = validate(session, "EMAIL", code);

        assertThat(fifth.getResponse().getStatus()).as("AC-22 5 回目: %s", body(fifth)).isEqualTo(429);
        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(errorCode(after)).as("AC-22 以後は再ログイン要").isEqualTo("AUTH_073");
        assertThat(valkey.liveKeysMatching(LOGIN_OTP_KEY_MARK)).as("AC-22 セッション削除と共にコードも消える").isEmpty();
    }

    /** AC-22 境界: 送信から 9 分 59 秒後はまだ有効。 */
    @Test
    @DisplayName("AC-22 境界 送信から9分59秒後は有効")
    void 九分五十九秒は有効() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        valkey.advance(OTP_TTL.minusSeconds(1));

        MvcResult r = validate(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-22 9:59: %s", body(r)).isEqualTo(200);
    }

    /**
     * AC-22 境界: 送信から 10 分ちょうどでコードは期限切れ。正しいコードでも通らない（400。
     * セッションも送信時から 10 分で切れるため AUTH_073 / AUTH_074 のいずれか）。
     */
    @Test
    @DisplayName("AC-22 境界 送信から10分ちょうどで期限切れ")
    void 十分ちょうどで期限切れ() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        valkey.advance(OTP_TTL);

        MvcResult r = validate(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-22 10:00: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).as("AC-22 期限切れは不正扱い").isIn("AUTH_073", "AUTH_074");
    }

    /**
     * AC-22: 未送信（このセッションで一度も送っていない）の EMAIL 検証は不正（AUTH_074）で、セッションは存続する
     * （正しい TOTP がその後通る）。
     */
    @Test
    @DisplayName("AC-22 未送信のEMAIL検証は不正・セッションは存続")
    void 未送信は不正() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);

        MvcResult r = validate(session, "EMAIL", "123456");
        MvcResult ok = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-22 未送信: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
        assertThat(ok.getResponse().getStatus()).as("AC-22 セッション存続: %s", body(ok)).isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-23 セッション跨ぎ・用途の相互流用不可
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-23: 同一ユーザーのセッション A で送ったコードはセッション B では通らない（AUTH_074）。
     * A では引き続き通る（B での拒否はコード不正ではなく紐付けによるものであることの確認）。
     */
    @Test
    @DisplayName("AC-23 セッションAのコードはセッションBで使えない")
    void セッション跨ぎは不可() throws Exception {
        MfaUser u = totpUser();
        String sessionA = loginForMfaSession(u);
        String sessionB = loginForMfaSession(u);
        String codeA = sendLoginOtpAndGetCode(u, sessionA);

        MvcResult onB = validate(sessionB, "EMAIL", codeA);
        MvcResult onA = validate(sessionA, "EMAIL", codeA);

        assertThat(onB.getResponse().getStatus()).as("AC-23 B で A のコード: %s", body(onB)).isEqualTo(400);
        assertThat(errorCode(onB)).isEqualTo("AUTH_074");
        assertThat(onA.getResponse().getStatus()).as("AC-23 A では有効: %s", body(onA)).isEqualTo(200);
    }

    /** AC-23: 別ユーザーのセッションで送ったコードも通らない。 */
    @Test
    @DisplayName("AC-23 別ユーザーのコードは使えない")
    void 別ユーザーのコードは不可() throws Exception {
        MfaUser a = totpUser();
        MfaUser b = totpUser();
        String sessionA = loginForMfaSession(a);
        String sessionB = loginForMfaSession(b);
        String codeA = sendLoginOtpAndGetCode(a, sessionA);

        MvcResult r = validate(sessionB, "EMAIL", codeA);

        assertThat(r.getResponse().getStatus()).as("AC-23: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
    }

    /**
     * AC-23: 設定用（SETUP）コードはログイン検証に使えない。メール 2FA 設定を始めた後に（別経路で）TOTP が
     * 有効になったユーザーが、ログインで設定用コードを出しても AUTH_074。
     */
    @Test
    @DisplayName("AC-23 設定用コードはログイン検証に使えない")
    void 設定用コードはログインに使えない() throws Exception {
        long userId = plainUser();
        String setupCode = startSetupAndGetCode(userId);
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        twoFactorAuthRepository.save(TwoFactorAuthEntity.builder()
                .userId(userId)
                .method(TwoFactorMethod.TOTP)
                .totpSecret(encryptionService.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"))
                .backupCodes("[]")
                .isEnabled(true)
                .verifiedAt(LocalDateTime.now())
                .build());
        String session = loginForMfaSession(new MfaUser(userId, email, "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", List.of()));

        MvcResult r = validate(session, "EMAIL", setupCode);

        assertThat(r.getResponse().getStatus()).as("AC-23 SETUP→LOGIN: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
    }

    /**
     * AC-23: ログイン用（LOGIN）コードはメール 2FA の有効化に使えない。ログイン用コードを受け取った後に
     * 2FA が外れた（行が消えた）ユーザーが、そのコードで有効化しようとしても AUTH_074 で有効化されない。
     */
    @Test
    @DisplayName("AC-23 ログイン用コードはメール2FA有効化に使えない")
    void ログイン用コードは有効化に使えない() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String loginCode = sendLoginOtpAndGetCode(u, session);
        jdbcTemplate.update("DELETE FROM two_factor_auth WHERE user_id = ?", u.userId());

        MvcResult r = enableEmail(u.userId(), loginCode);

        assertThat(r.getResponse().getStatus()).as("AC-23 LOGIN→SETUP: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
        assertThat(twoFactorRow(u.userId())).as("AC-23 有効化されない").isNull();
    }

    /** AC-23: 本人確認用（REAUTH）コードはログイン検証に使えない。 */
    @Test
    @DisplayName("AC-23 本人確認用コードはログイン検証に使えない")
    void 本人確認用コードはログインに使えない() throws Exception {
        MfaUser u = totpUser();
        String reauthCode = sendReauthAndGetCode(u.userId());
        String session = loginForMfaSession(u);

        MvcResult r = validate(session, "EMAIL", reauthCode);

        assertThat(r.getResponse().getStatus()).as("AC-23 REAUTH→LOGIN: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
    }

    /** AC-23: ログイン用コードは本人確認（無効化）に使えない。無効化は失敗し 2FA は有効のまま。 */
    @Test
    @DisplayName("AC-23 ログイン用コードは無効化の本人確認に使えない")
    void ログイン用コードは無効化に使えない() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String loginCode = sendLoginOtpAndGetCode(u, session);

        MvcResult r = disable(u.userId(), PASSWORD, "EMAIL", loginCode);

        assertThat(r.getResponse().getStatus()).as("AC-23 LOGIN→REAUTH: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_080");
        assertThat(twoFactorRow(u.userId())).as("AC-23 2FA は有効のまま").isNotNull();
    }

    /** AC-23: 本人確認用コードはメール 2FA の有効化に使えない。 */
    @Test
    @DisplayName("AC-23 本人確認用コードはメール2FA有効化に使えない")
    void 本人確認用コードは有効化に使えない() throws Exception {
        MfaUser u = totpUser();
        String reauthCode = sendReauthAndGetCode(u.userId());
        jdbcTemplate.update("DELETE FROM two_factor_auth WHERE user_id = ?", u.userId());

        MvcResult r = enableEmail(u.userId(), reauthCode);

        assertThat(r.getResponse().getStatus()).as("AC-23 REAUTH→SETUP: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
        assertThat(twoFactorRow(u.userId())).as("AC-23 有効化されない").isNull();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-24 主手段・代替・メールなし
    // ═════════════════════════════════════════════════════════════

    /** AC-24: EMAIL 方式ユーザーはメールOTP を主手段としてログインできる。 */
    @Test
    @DisplayName("AC-24 EMAIL方式ユーザーはメールOTPでログインできる")
    void EMAIL方式は主手段() throws Exception {
        MfaUser u = emailUser();
        MvcResult login = login(u.email());
        JsonNode data = json(login).path("data");
        assertThat(data.path("primaryMethod").asText(null)).as("AC-24 主手段は EMAIL").isEqualTo("EMAIL");
        String session = data.path("mfaSessionToken").asText();
        String code = sendLoginOtpAndGetCode(u, session);

        MvcResult r = validate(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-24: %s", body(r)).isEqualTo(200);
    }

    /** AC-24: TOTP 方式ユーザーもメールOTP を代替手段として使える（availableMethods に EMAIL を含む）。 */
    @Test
    @DisplayName("AC-24 TOTPユーザーもメールOTPを代替手段に使える")
    void TOTPユーザーの代替手段() throws Exception {
        MfaUser u = totpUser();
        MvcResult login = login(u.email());
        JsonNode data = json(login).path("data");
        assertThat(texts(data.path("availableMethods"))).as("AC-24 代替に EMAIL").contains("EMAIL");
        String session = data.path("mfaSessionToken").asText();
        String code = sendLoginOtpAndGetCode(u, session);

        MvcResult r = validate(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-24: %s", body(r)).isEqualTo(200);
    }

    /**
     * AC-24: EMAIL 方式ユーザー（totp_secret NULL）に TOTP を出しても不正扱い（400 AUTH_074）で、
     * NPE 等の 500 にならない。
     */
    @Test
    @DisplayName("AC-24 EMAIL方式ユーザーにTOTPを出すと不正扱い（500にならない）")
    void EMAIL方式にTOTPは不正() throws Exception {
        MfaUser u = emailUser();
        String session = loginForMfaSession(u);

        MvcResult r = validate(session, "TOTP", "123456");

        assertThat(r.getResponse().getStatus()).as("AC-24: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_074");
    }

    /**
     * AC-24: メールなし（内部プレースホルダ）の TOTP ユーザーは availableMethods に EMAIL を含めず、
     * 送信は 400 AUTH_081・outbox 行なし。送信失敗でセッションは消費されない（TOTP が通る）。
     */
    @Test
    @DisplayName("AC-24 メールなしTOTPユーザーはEMAIL非提示・送信は400 AUTH_081")
    void メールなしユーザーは送信不可() throws Exception {
        MfaUser base = totpUser();
        String placeholder = placeholderEmail();
        updateEmail(base.userId(), placeholder);
        MfaUser u = new MfaUser(base.userId(), placeholder, base.totpSecret(), base.backupCodes());
        MvcResult login = login(u.email());
        JsonNode data = json(login).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("前提: MFA 要求").isTrue();
        assertThat(texts(data.path("availableMethods"))).as("AC-24 EMAIL を含めない")
                .doesNotContain("EMAIL").contains("TOTP", "BACKUP_CODE");
        String session = data.path("mfaSessionToken").asText();

        MvcResult send = sendLoginOtp(session);
        MvcResult totp = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(send.getResponse().getStatus()).as("AC-24 送信: %s", body(send)).isEqualTo(400);
        assertThat(errorCode(send)).isEqualTo("AUTH_081");
        Thread.sleep(300);
        assertThat(otpOutboxCount(u.userId())).as("AC-24 enqueue しない").isZero();
        assertThat(totp.getResponse().getStatus()).as("AC-24 セッション非消費: %s", body(totp)).isEqualTo(200);
    }

    /**
     * AC-24: メールなしの EMAIL 方式ユーザー（有効化後にアドレスがプレースホルダへ変わった）は、
     * 主手段 EMAIL のまま availableMethods はバックアップコードのみ。送信は 400 AUTH_081。
     */
    @Test
    @DisplayName("AC-24 メールなしEMAIL方式ユーザーはバックアップコードのみ提示・送信は400")
    void メールなしEMAIL方式() throws Exception {
        MfaUser base = emailUser();
        String placeholder = placeholderEmail();
        updateEmail(base.userId(), placeholder);
        MvcResult login = login(placeholder);
        JsonNode data = json(login).path("data");
        assertThat(texts(data.path("availableMethods"))).as("AC-24 バックアップコードのみ")
                .containsExactly("BACKUP_CODE");
        String session = data.path("mfaSessionToken").asText();

        MvcResult send = sendLoginOtp(session);

        assertThat(send.getResponse().getStatus()).as("AC-24 送信: %s", body(send)).isEqualTo(400);
        assertThat(errorCode(send)).isEqualTo("AUTH_081");
    }

    /** AC-24: 送信時点で判定する（ログイン後にアドレスがプレースホルダへ変わったら送らない）。 */
    @Test
    @DisplayName("AC-24 ログイン後にメールなしになったら送信は400")
    void ログイン後にメールなし() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        updateEmail(u.userId(), placeholderEmail());

        MvcResult send = sendLoginOtp(session);

        assertThat(send.getResponse().getStatus()).as("AC-24: %s", body(send)).isEqualTo(400);
        assertThat(errorCode(send)).isEqualTo("AUTH_081");
    }
}
