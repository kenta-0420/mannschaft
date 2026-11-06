package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.service.AuthTokenService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: MFA セッションの試行上限とユーザー単位ロック（総当たり対策）。
 *
 * <p>穴: 現行 {@code validateTotp} は照合<b>前</b>に MFA セッションを削除する「単回使用」で総当たりを防いでいたため、
 * 1 回の打ち間違いで再ログインを強いられ、バックアップコード・メールOTP の導入（複数回の試行が要る）と両立しない。
 * 裁可事項 6: 「全手段合計 5 回失敗まで（照合前に原子 INCR）」＋「ユーザー単位 10 回/30 分でロック」へ置き換える。</p>
 *
 * <p>対象 AC: AC-14（セッション試行上限）、AC-16（ユーザー単位ロック）、AC-17（不在/期限切れの同一応答）、
 * AC-46（ロック後はロック前発行のセッションも全方式で拒否・30 分境界で解除）。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 MFA セッション試行上限とユーザー単位ロック（AC-14/16/17/46）")
class MfaSessionAttemptIT extends MfaItSupport {

    private static final String EMAIL_SEND_PATH = "/api/v1/auth/2fa/email/send";

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private AuthTokenService authTokenService;

    // ═════════════════════════════════════════════════════════════
    // AC-14 セッション試行上限
    // ═════════════════════════════════════════════════════════════

    /** AC-14: 1 回失敗してもセッションは存続し、正しいコードで再試行できる。 */
    @Test
    @DisplayName("AC-14 1回失敗してもセッションは存続し再試行できる")
    void 一回失敗してもセッション存続() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult wrong = validate(session, "TOTP", wrongTotp(u.totpSecret()));
        MvcResult ok = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(wrong.getResponse().getStatus()).as("AC-14 1 回目は不正: %s", body(wrong)).isEqualTo(400);
        assertThat(errorCode(wrong)).isEqualTo("AUTH_074");
        assertThat(ok.getResponse().getStatus()).as("AC-14 同じセッションで再試行して成功: %s", body(ok)).isEqualTo(200);
    }

    /** AC-14 境界: 4 回失敗してもセッションは存続する（5 回目に達していない）。 */
    @Test
    @DisplayName("AC-14 境界 4回失敗後も正しいコードで通る")
    void 四回失敗後も通る() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        for (int i = 0; i < 4; i++) {
            validate(session, "TOTP", wrongTotp(u.totpSecret()));
        }

        MvcResult ok = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(ok.getResponse().getStatus()).as("AC-14 4 回失敗後の成功: %s", body(ok)).isEqualTo(200);
    }

    /**
     * AC-14: 全手段合計（TOTP・バックアップコード・未送信のメールOTP）で 5 回目の失敗でセッション削除（AUTH_075）。
     * 以後は正しいコードでも「再ログイン要」（AUTH_073）。
     */
    @Test
    @DisplayName("AC-14 全手段合計5回目でセッション削除・以後は再ログイン要")
    void 全手段合計五回目でセッション削除() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        List<String> codes = new ArrayList<>();
        codes.add(errorCode(validate(session, "TOTP", wrongTotp(u.totpSecret()))));
        codes.add(errorCode(validate(session, "BACKUP_CODE", wrongBackupCode(u))));
        codes.add(errorCode(validate(session, "EMAIL", "123456")));
        codes.add(errorCode(validate(session, "TOTP", wrongTotp(u.totpSecret()))));

        MvcResult fifth = validate(session, "BACKUP_CODE", wrongBackupCode(u));
        MvcResult after = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(codes).as("AC-14 1〜4 回目は不正（未送信のメールOTP も不正扱い）").containsOnly("AUTH_074");
        assertThat(fifth.getResponse().getStatus()).as("AC-14 5 回目: %s", body(fifth)).isEqualTo(429);
        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(after.getResponse().getStatus()).as("AC-14 削除後は正しいコードでも不可: %s", body(after))
                .isEqualTo(400);
        assertThat(errorCode(after)).as("AC-14 再ログイン要").isEqualTo("AUTH_073");
        assertThat(valkey.rawGet(MFA_SESSION_KEY_PREFIX + authTokenService.hashToken(session)))
                .as("AC-14 セッションキーは削除されている").isNull();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-16 ユーザー単位ロック
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-16: ユーザー単位で MFA 失敗 10 回（30 分窓）に達するとアカウントを 30 分ロック（AUTH_003）し、
     * LoginFailedEvent(reason=MFA_FAILED) を発行する。セッションを作り直しても回避できない。
     */
    @Test
    @DisplayName("AC-16 ユーザー単位MFA失敗10回でロック・セッション作り直しで回避不可")
    void ユーザー単位十回失敗でロック() throws Exception {
        MfaUser u = createTotpUser();
        failFiveTimes(u, loginForMfaSession(u));
        failFiveTimes(u, loginForMfaSession(u));

        assertLockedForNewSession(u);
        assertThat(events.stream(LoginFailedEvent.class).map(LoginFailedEvent::getReason))
                .as("AC-16 LoginFailedEvent(MFA_FAILED)").contains("MFA_FAILED");
    }

    /** AC-16 境界: 9 回ではロックしない（2 本目のセッションで正しいコードが通る）。 */
    @Test
    @DisplayName("AC-16 境界 9回失敗ではロックしない")
    void 九回失敗ではロックしない() throws Exception {
        MfaUser u = createTotpUser();
        failFiveTimes(u, loginForMfaSession(u));
        String second = loginForMfaSession(u);
        for (int i = 0; i < 4; i++) {
            validate(second, "TOTP", wrongTotp(u.totpSecret()));
        }

        MvcResult ok = validate(second, "TOTP", currentTotp(u.totpSecret()));

        assertThat(ok.getResponse().getStatus()).as("AC-16 9 回失敗ではロックされない: %s", body(ok)).isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-17 不在/期限切れの同一応答
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-17: 存在しない MFA セッションと期限切れの MFA セッションは同一応答（AUTH_073・同ステータス・同メッセージ）。
     * 応答にユーザーを特定できる情報（メールアドレス等）を含めない。
     */
    @Test
    @DisplayName("AC-17 不在と期限切れのセッションは同一応答でユーザー特定情報を含まない")
    void 不在と期限切れは同一応答() throws Exception {
        MfaUser u = createTotpUser();
        String expired = loginForMfaSession(u);
        valkey.advance(Duration.ofMinutes(15).plusSeconds(1));

        MvcResult unknown = validate(UUID.randomUUID().toString(), "TOTP", "123456");
        MvcResult stale = validate(expired, "TOTP", "123456");
        MvcResult unknownBackup = validate(UUID.randomUUID().toString(), "BACKUP_CODE", "12345678");
        MvcResult staleBackup = validate(expired, "BACKUP_CODE", "12345678");

        for (MvcResult r : List.of(unknown, stale, unknownBackup, staleBackup)) {
            assertThat(r.getResponse().getStatus()).as("AC-17: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).as("AC-17 セッション無効").isEqualTo("AUTH_073");
            assertThat(body(r)).as("AC-17 ユーザー特定情報なし").doesNotContain(u.email())
                    .doesNotContain("\"userId\"");
        }
        assertThat(json(stale).path("error").path("message").asText())
                .as("AC-17 同一メッセージ").isEqualTo(json(unknown).path("error").path("message").asText());
    }

    // ═════════════════════════════════════════════════════════════
    // AC-46 ロック後の既存セッション
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-46: ユーザーロック後は、ロック前に発行済みの MFA セッションも全方式（TOTP・バックアップコード・
     * メールOTP 送信）で拒否（AUTH_003）。30 分の境界で解除され、新しいログインで通る。
     */
    @Test
    @DisplayName("AC-46 ロック後はロック前のセッションも全方式で拒否・30分境界で解除")
    void ロック後はロック前のセッションも拒否し三十分で解除() throws Exception {
        MfaUser u = createTotpUser();
        String preLock = loginForMfaSession(u);
        failFiveTimes(u, loginForMfaSession(u));
        failFiveTimes(u, loginForMfaSession(u));

        MvcResult totp = validate(preLock, "TOTP", currentTotp(u.totpSecret()));
        MvcResult backup = validate(preLock, "BACKUP_CODE", u.backupCodes().get(0));
        MvcResult send = mockMvc.perform(post(EMAIL_SEND_PATH).servletPath(EMAIL_SEND_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaSessionToken", preLock))))
                .andReturn();

        assertThat(errorCode(totp)).as("AC-46 TOTP はロックで拒否: %s", body(totp)).isEqualTo("AUTH_003");
        assertThat(errorCode(backup)).as("AC-46 バックアップコードはロックで拒否: %s", body(backup)).isEqualTo("AUTH_003");
        assertThat(errorCode(send)).as("AC-46 メールOTP 送信もロックで拒否: %s", body(send)).isEqualTo("AUTH_003");
        assertThat(backupCodeCount(u.userId())).as("AC-46 ロック中はバックアップコードを消費しない").isEqualTo(8);

        valkey.advance(Duration.ofMinutes(30).minusSeconds(5));
        assertLockedForNewSession(u);

        valkey.advance(Duration.ofSeconds(10));
        MvcResult ok = validate(loginForMfaSession(u), "TOTP", currentTotp(u.totpSecret()));
        assertThat(ok.getResponse().getStatus()).as("AC-46 30 分経過で解除: %s", body(ok)).isEqualTo(200);
    }

    // ─────────────────────────────────────────────────────────────

    private void failFiveTimes(MfaUser u, String session) throws Exception {
        for (int i = 0; i < 5; i++) {
            validate(session, "TOTP", wrongTotp(u.totpSecret()));
        }
    }

    /** 新しいログインを試みてもロックで弾かれる（ログイン時点か、validate 時点かは問わない）。 */
    private void assertLockedForNewSession(MfaUser u) throws Exception {
        MvcResult login = login(u.email());
        if (login.getResponse().getStatus() == 200 && json(login).path("data").path("mfaRequired").asBoolean()) {
            String token = json(login).path("data").path("mfaSessionToken").asText();
            MvcResult r = validate(token, "TOTP", currentTotp(u.totpSecret()));
            assertThat(errorCode(r)).as("AC-16/46 新しいセッションでもロック: %s", body(r)).isEqualTo("AUTH_003");
        } else {
            assertThat(errorCode(login)).as("AC-16/46 ログイン時点でロック: %s", body(login)).isEqualTo("AUTH_003");
        }
    }
}
