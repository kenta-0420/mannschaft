package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.MfaBackupCodeUsedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: 2FA ログイン検証（{@code POST /api/v1/auth/2fa/validate}）の要素拡張。
 *
 * <p>対象 AC: AC-7/8/10/11/12（バックアップコードでのログイン）、AC-27（MFA 要求応答の方式情報）、
 * AC-53（旧 {@code {mfaSessionToken, totpCode}} 互換と新旧競合）。</p>
 *
 * <p>MFA セッションは実ログイン API で発行し、validate は実 Security フィルタ越しに叩く。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA ログイン検証の要素拡張（AC-7/8/10/11/12/27/53）")
class MfaLoginValidationIT extends MfaItSupport {

    @Autowired
    private ApplicationEvents events;

    // ═════════════════════════════════════════════════════════════
    // B. バックアップコード
    // ═════════════════════════════════════════════════════════════


    /**
     * AC-7: validate(BACKUP_CODE) に正しい未使用 8 桁でトークン発行、backup_codes から当該 1 件だけ削除、
     * LoginSuccessEvent(method=BACKUP_CODE, ip・UA 非 null)。
     */
    @Test
    @DisplayName("AC-7 正しい未使用バックアップコードでトークン発行・1件削除・LoginSuccessEventにip/UA")
    void 正しいバックアップコードでログインできる() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult result = validate(session, "BACKUP_CODE", u.backupCodes().get(3));

        assertThat(result.getResponse().getStatus()).as("AC-7: %s", body(result)).isEqualTo(200);
        assertThat(json(result).path("data").path("accessToken").asText(null)).as("AC-7 アクセストークン").isNotBlank();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .as("AC-7 Cookie 発行").anySatisfy(c -> assertThat(c).startsWith("access_token="));
        assertThat(backupCodeCount(u.userId())).as("AC-7 使用した 1 件だけ削除").isEqualTo(7);
        List<LoginSuccessEvent> success = events.stream(LoginSuccessEvent.class).toList();
        assertThat(success).as("AC-7 LoginSuccessEvent").hasSize(1);
        LoginSuccessEvent ev = success.get(0);
        assertThat(ev.getUserId()).isEqualTo(u.userId());
        assertThat(ev.getMethod()).as("AC-7 method").isEqualTo("BACKUP_CODE");
        assertThat(ev.getIpAddress()).as("AC-7 ip 非 null").isNotBlank();
        assertThat(ev.getUserAgent()).as("AC-7 UA 非 null").isEqualTo(USER_AGENT);
    }

    /** AC-8: 使用済みバックアップコードの再送は失敗し、他の不正コードと同じエラーになる。 */
    @Test
    @DisplayName("AC-8 使用済みバックアップコードの再送は他の不正と同じエラー")
    void 使用済みバックアップコードは不正と同じエラー() throws Exception {
        MfaUser u = createTotpUser();
        String used = u.backupCodes().get(0);
        MvcResult first = validate(loginForMfaSession(u), "BACKUP_CODE", used);
        assertThat(first.getResponse().getStatus()).as("AC-8 前提: 1 回目は成功: %s", body(first)).isEqualTo(200);

        MvcResult reuse = validate(loginForMfaSession(u), "BACKUP_CODE", used);
        MvcResult wrong = validate(loginForMfaSession(u), "BACKUP_CODE", wrongBackupCode(u));

        assertThat(reuse.getResponse().getStatus()).as("AC-8 再送は失敗: %s", body(reuse)).isEqualTo(400);
        assertThat(errorCode(reuse)).as("AC-8 再送のエラーコード").isEqualTo("AUTH_074");
        assertThat(reuse.getResponse().getStatus()).as("AC-8 不正と同ステータス")
                .isEqualTo(wrong.getResponse().getStatus());
        assertThat(errorCode(reuse)).as("AC-8 不正と同コード").isEqualTo(errorCode(wrong));
        assertThat(json(reuse).path("error").path("message").asText())
                .as("AC-8 不正と同メッセージ").isEqualTo(json(wrong).path("error").path("message").asText());
        assertThat(backupCodeCount(u.userId())).as("AC-8 失敗では減らない").isEqualTo(7);
    }

    /**
     * AC-11: 全件使用済み（{@code []}）は不正扱い（AUTH_074）で、試行回数に計上される
     * （5 回目で試行上限 AUTH_075）。
     */
    @Test
    @DisplayName("AC-11 全件使用済みは不正扱いで試行に計上される")
    void 全件使用済みは不正扱いで試行計上() throws Exception {
        MfaUser u = createMfaUser(TwoFactorMethod.TOTP, 0, passwordEncoder::encode);
        String session = loginForMfaSession(u);

        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            MvcResult r = validate(session, "BACKUP_CODE", String.format("%08d", 10_000_000 + i));
            assertThat(r.getResponse().getStatus()).as("AC-11 %d 回目: %s", i, body(r)).isEqualTo(400);
            codes.add(errorCode(r));
        }
        MvcResult fifth = validate(session, "BACKUP_CODE", "10000005");

        assertThat(codes).as("AC-11 1〜4 回目は不正").containsOnly("AUTH_074");
        assertThat(fifth.getResponse().getStatus()).as("AC-11 5 回目は試行上限: %s", body(fifth)).isEqualTo(429);
        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
    }

    /**
     * AC-12（BE のイベント・応答部分）: 使用後、応答に backupCodesRemaining（消費後の残数）と method、
     * MfaBackupCodeUsedEvent（監査 MFA_BACKUP_CODE_USED・通知メールの起点）が発行される。
     */
    @Test
    @DisplayName("AC-12 バックアップコード使用で応答に残数・MfaBackupCodeUsedEventが発行される")
    void バックアップコード使用で残数を返しイベントを出す() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult result = validate(loginForMfaSession(u), "BACKUP_CODE", u.backupCodes().get(7));

        assertThat(result.getResponse().getStatus()).as("AC-12: %s", body(result)).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("backupCodesRemaining").asInt(-1)).as("AC-12 残数").isEqualTo(7);
        assertThat(data.path("method").asText(null)).as("AC-12 method").isEqualTo("BACKUP_CODE");
        List<MfaBackupCodeUsedEvent> used = events.stream(MfaBackupCodeUsedEvent.class).toList();
        assertThat(used).as("AC-12 MfaBackupCodeUsedEvent").hasSize(1);
        assertThat(used.get(0).getUserId()).isEqualTo(u.userId());
        assertThat(used.get(0).getBackupCodesRemaining()).isEqualTo(7);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-10 形式検証
    // ═════════════════════════════════════════════════════════════


    /** AC-10: TOTP/EMAIL は ^\d{6}$、BACKUP_CODE は ^\d{8}$。null・空・違反は 400（COMMON_001）。 */
    @Test
    @DisplayName("AC-10 形式違反・null・空は400（入力不備）")
    void 形式違反は400() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        List<String[]> cases = List.of(
                new String[]{"TOTP", "12345"},
                new String[]{"TOTP", "1234567"},
                new String[]{"TOTP", "12a456"},
                new String[]{"TOTP", ""},
                new String[]{"TOTP", null},
                new String[]{"EMAIL", "12345"},
                new String[]{"EMAIL", "1234567"},
                new String[]{"BACKUP_CODE", "1234567"},
                new String[]{"BACKUP_CODE", "123456789"},
                new String[]{"BACKUP_CODE", "1234-678"},
                new String[]{"BACKUP_CODE", null});

        for (String[] c : cases) {
            MvcResult r = validate(session, c[0], c[1]);
            assertThat(r.getResponse().getStatus()).as("AC-10 method=%s code=%s: %s", c[0], c[1], body(r))
                    .isEqualTo(400);
            assertThat(errorCode(r)).as("AC-10 入力不備（試行には計上しない） method=%s code=%s", c[0], c[1])
                    .isEqualTo("COMMON_001");
        }
    }

    /** AC-10: 形式違反は試行に計上しない（何度送ってもセッションは消費されず、正しいコードで通る）。 */
    @Test
    @DisplayName("AC-10 形式違反は試行に計上せずセッションも消費しない")
    void 形式違反は試行非計上() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        for (int i = 0; i < 6; i++) {
            validate(session, "TOTP", "12345");
            validate(session, "BACKUP_CODE", "1234567");
        }

        MvcResult ok = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(ok.getResponse().getStatus()).as("AC-10 形式違反 12 回の後も正しいコードで通る: %s", body(ok))
                .isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-27 MFA 要求応答
    // ═════════════════════════════════════════════════════════════


    /** AC-27: TOTP ユーザーは primaryMethod=TOTP、availableMethods に TOTP/BACKUP_CODE/EMAIL（代替）。互換項目維持。 */
    @Test
    @DisplayName("AC-27 TOTPユーザーは primaryMethod=TOTP・availableMethods=TOTP/BACKUP_CODE/EMAIL")
    void TOTPユーザーの方式情報() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult result = login(u.email());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-27 互換: mfaRequired").isTrue();
        assertThat(data.path("mfaSessionToken").asText(null)).as("AC-27 互換: mfaSessionToken").isNotBlank();
        assertThat(data.path("primaryMethod").asText(null)).as("AC-27 primaryMethod").isEqualTo("TOTP");
        assertThat(texts(data.path("availableMethods"))).as("AC-27 availableMethods")
                .containsExactlyInAnyOrder("TOTP", "BACKUP_CODE", "EMAIL");
    }

    /** AC-27: EMAIL ユーザーは primaryMethod=EMAIL、TOTP を含めない。 */
    @Test
    @DisplayName("AC-27 メール方式ユーザーは primaryMethod=EMAIL・TOTPを含めない")
    void メール方式ユーザーの方式情報() throws Exception {
        MfaUser u = createEmailUser();

        MvcResult result = login(u.email());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("primaryMethod").asText(null)).as("AC-27 primaryMethod").isEqualTo("EMAIL");
        assertThat(texts(data.path("availableMethods"))).as("AC-27 availableMethods")
                .containsExactlyInAnyOrder("EMAIL", "BACKUP_CODE");
    }

    private List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ═════════════════════════════════════════════════════════════
    // AC-53 旧形式互換
    // ═════════════════════════════════════════════════════════════


    /** AC-53: 旧 {@code {mfaSessionToken, totpCode}} で TOTP ログインに成功する（回帰防止）。 */
    @Test
    @DisplayName("AC-53 旧形式 mfaSessionToken+totpCode でTOTP成功")
    void 旧形式でTOTP成功() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult r = validateRaw(objectMapper.writeValueAsString(
                Map.of("mfaSessionToken", session, "totpCode", currentTotp(u.totpSecret()))));

        assertThat(r.getResponse().getStatus()).as("AC-53 旧形式: %s", body(r)).isEqualTo(200);
    }

    /** AC-53: method 欠落（新形式 code のみ）は TOTP とみなす。 */
    @Test
    @DisplayName("AC-53 method欠落はTOTPとみなす")
    void method欠落はTOTP() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult r = validate(session, null, currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-53 method 欠落: %s", body(r)).isEqualTo(200);
    }

    /** AC-53: method が未知の値は 400（入力不備）。 */
    @Test
    @DisplayName("AC-53 未知のmethodは400")
    void 未知のmethodは400() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);

        MvcResult r = validate(session, "SMS", "123456");

        assertThat(r.getResponse().getStatus()).as("AC-53 未知 method: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("COMMON_001");
    }

    /**
     * AC-53: 新旧 code の競合（{@code code} と {@code totpCode} を両方指定）は 400（入力不備）。
     * どちらか一方を黙って優先しない（片方が正しいコードでも通さない）。
     */
    @Test
    @DisplayName("AC-53 codeとtotpCodeの両方指定は400")
    void 新旧code競合は400() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        String valid = currentTotp(u.totpSecret());

        MvcResult r = validateRaw(objectMapper.writeValueAsString(Map.of(
                "mfaSessionToken", session, "method", "TOTP", "code", valid, "totpCode", wrongTotp(u.totpSecret()))));

        assertThat(r.getResponse().getStatus()).as("AC-53 新旧競合: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("COMMON_001");
    }
}
