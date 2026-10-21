package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * CMP-261012-0910 試練: ログイン用メールOTP の送信（{@code POST /api/v1/auth/2fa/email/send}）。
 *
 * <p>対象 AC: AC-19（生成・ハッシュ保管・outbox enqueue・応答・セッション延長・非消費）、
 * AC-20（再送待ち 60 秒・セッション 5 回・ユーザー 10 回/時・IP 上限）、AC-21（再送で旧コード無効）。</p>
 *
 * <p>MFA セッションは実ログイン API で発行し、送信・検証は実 Security フィルタ越しに叩く。DB（email_outbox）は実物、
 * Valkey のみ {@link MfaValkeyFake}。境界値（59/60 秒、5/6 回、1 時間窓）は {@link MfaValkeyFake#advance} で時計を送る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 ログイン用メールOTP 送信（AC-19/20/21）")
class MfaEmailOtpSendIT extends MfaEmailOtpItSupport {

    // ═════════════════════════════════════════════════════════════
    // AC-19 送信の基本契約
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-19: 送信成功の応答は {@code {maskedEmail, expiresInSeconds=600, resendAfterSeconds=60}}。
     * マスク済みアドレスは平文アドレスそのものではなく、ローカル部を伏せる（ドメインは残す）。
     */
    @Test
    @DisplayName("AC-19 応答はマスク済みアドレス・有効期限600秒・再送待ち60秒")
    void 送信応答の契約() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);

        MvcResult r = sendLoginOtp(session);

        assertThat(r.getResponse().getStatus()).as("AC-19: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        String masked = data.path("maskedEmail").asText(null);
        String localPart = u.email().substring(0, u.email().indexOf('@'));
        assertThat(masked).as("AC-19 maskedEmail").isNotBlank()
                .isNotEqualTo(u.email())
                .doesNotContain(localPart)
                .endsWith("@example.com");
        assertThat(data.path("expiresInSeconds").asLong(-1)).as("AC-19 expiresInSeconds").isEqualTo(600);
        assertThat(data.path("resendAfterSeconds").asLong(-1)).as("AC-19 resendAfterSeconds").isEqualTo(60);
        assertThat(body(r)).as("AC-19 応答に平文アドレスを含めない").doesNotContain(u.email());
    }

    /**
     * AC-19: email_outbox に MFA_EMAIL_OTP 行が 1 件 enqueue され、宛先は本人の登録アドレス・locale は ja 固定
     * （裁可事項 9）・payload に 6 桁コードを持つ。
     */
    @Test
    @DisplayName("AC-19 email_outboxにMFA_EMAIL_OTP行が本人宛・ja・6桁コード付きで1件入る")
    void outboxに一件入る() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);

        MvcResult r = sendLoginOtp(session);

        assertThat(r.getResponse().getStatus()).as("AC-19: %s", body(r)).isEqualTo(200);
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(u.userId(), 1);
        assertThat(rows).as("AC-19 MFA_EMAIL_OTP 行は 1 件").hasSize(1);
        OtpOutboxRow row = rows.get(0);
        assertThat(row.toAddress()).as("AC-19 宛先は本人の登録アドレス").isEqualTo(u.email());
        assertThat(row.locale()).as("AC-19 ja 固定").isEqualTo("ja");
        assertThat(codeOf(row)).as("AC-19 6 桁コード").matches("\\d{6}");
    }

    /**
     * AC-19: Valkey には平文コードではなくハッシュを、MFA セッション（の hash）に紐付けたキーで TTL 10 分保管する。
     * キーに生のセッショントークンを含めない。
     */
    @Test
    @DisplayName("AC-19 Valkeyにはコードのハッシュをセッション紐付けキーでTTL10分保管し平文を置かない")
    void Valkeyにはハッシュを置く() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);

        String code = sendLoginOtpAndGetCode(u, session);

        Set<String> keys = valkey.liveKeysMatching(LOGIN_OTP_KEY_MARK);
        assertThat(keys).as("AC-19 ログイン用 OTP キー（%s）が 1 つ", LOGIN_OTP_KEY_MARK).hasSize(1);
        String key = keys.iterator().next();
        assertThat(key).as("AC-19 キーに生のセッショントークンを含めない").doesNotContain(session);
        assertThat(key).as("AC-19 キーはセッションの hash に紐付く")
                .contains(authTokenService.hashToken(session));
        assertThat(valkey.rawGet(key)).as("AC-19 値に平文コードを置かない").doesNotContain(code);
        assertThat(valkey.ttlMillis(key)).as("AC-19 TTL は 10 分")
                .isBetween(OTP_TTL.minusSeconds(10).toMillis(), OTP_TTL.toMillis());
        assertThat(valkey.liveKeysMatching(".*")).as("AC-19 どのキーの値にも平文コードを置かない")
                .allSatisfy(k -> assertThat(String.valueOf(valkey.rawGet(k))).doesNotContain(code));
    }

    /**
     * AC-19: 送信は MFA セッションを消費しない（直後に同じセッションで TOTP 検証が通る）。
     */
    @Test
    @DisplayName("AC-19 送信はセッションを消費しない")
    void 送信はセッションを消費しない() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);

        MvcResult send = sendLoginOtp(session);
        MvcResult ok = validate(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(send.getResponse().getStatus()).as("AC-19 送信: %s", body(send)).isEqualTo(200);
        assertThat(ok.getResponse().getStatus()).as("AC-19 送信後も同じセッションで検証できる: %s", body(ok))
                .isEqualTo(200);
    }

    /**
     * AC-19: 送信時に MFA セッションの TTL を 10 分へ延長する（ログイン直後は 5 分）。
     * 4 分経過時点で送信すると、セッションはそこから 10 分生きる（元の 5 分の期限を越えて検証できる）。
     */
    @Test
    @DisplayName("AC-19 送信でセッションTTLが10分へ延長され元の5分期限を越えて検証できる")
    void 送信でセッションTTL延長() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        assertThat(valkey.ttlMillis(sessionKey(session))).as("前提: ログイン直後のセッション TTL は 5 分以下")
                .isBetween(1L, Duration.ofMinutes(5).toMillis());
        valkey.advance(Duration.ofMinutes(4));

        String code = sendLoginOtpAndGetCode(u, session);

        assertThat(valkey.ttlMillis(sessionKey(session))).as("AC-19 送信後のセッション TTL は約 10 分")
                .isBetween(Duration.ofMinutes(9).toMillis(), Duration.ofMinutes(10).toMillis());
        valkey.advance(Duration.ofMinutes(3)); // ログインから 7 分（元の 5 分期限は過ぎている）
        MvcResult ok = validate(session, "EMAIL", code);
        assertThat(ok.getResponse().getStatus()).as("AC-19 延長後のセッションで検証できる: %s", body(ok))
                .isEqualTo(200);
    }

    /**
     * AC-19 境界: 延長はログインから最大 15 分まで。ログイン 10 分後に再送しても、セッション期限は
     * ログイン +15 分を越えない（TTL は 5 分以下）。+15 分を過ぎたら正しいコードでも再ログイン要（AUTH_073）。
     */
    @Test
    @DisplayName("AC-19 境界 セッション延長はログインから15分が上限")
    void セッション延長は15分が上限() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        valkey.advance(Duration.ofMinutes(4));
        sendLoginOtpAndGetCode(u, session);
        valkey.advance(Duration.ofMinutes(6)); // ログインから 10 分

        String code = sendLoginOtpAndGetCode(u, session);

        assertThat(valkey.ttlMillis(sessionKey(session))).as("AC-19 ログイン +15 分を越えて延長しない")
                .isBetween(1L, Duration.ofMinutes(5).toMillis());
        valkey.advance(Duration.ofMinutes(5).plusSeconds(1)); // ログインから 15 分 1 秒
        MvcResult late = validate(session, "EMAIL", code);
        assertThat(late.getResponse().getStatus()).as("AC-19 15 分上限後: %s", body(late)).isEqualTo(400);
        assertThat(errorCode(late)).as("AC-19 15 分上限後は再ログイン要").isEqualTo("AUTH_073");
    }

    /** AC-19: 不在の MFA セッションでは送信せず AUTH_073、outbox 行も作らない。 */
    @Test
    @DisplayName("AC-19 期限切れセッションでは送信せずoutboxも作らない")
    void 期限切れセッションでは送信しない() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        valkey.advance(Duration.ofMinutes(5).plusSeconds(1));

        MvcResult r = sendLoginOtp(session);

        assertThat(r.getResponse().getStatus()).as("AC-19 期限切れ: %s", body(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("AUTH_073");
        Thread.sleep(300); // @Async enqueue の取りこぼし防止（行が無いことの確認）
        assertThat(otpOutboxCount(u.userId())).as("AC-19 期限切れでは enqueue しない").isZero();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-20 再送待ち・送信上限
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-20 境界: 前回送信から 59 秒後の再送は 429（AUTH_076）で、{@code Retry-After} に残り秒数（1〜60）を返す。
     * 60 秒後は送信できる。待ち中の再送は outbox 行を増やさない。
     */
    @Test
    @DisplayName("AC-20 境界 59秒後の再送は429で残り秒数・60秒後は送信できる")
    void 再送待ち59秒と60秒() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        sendLoginOtpAndGetCode(u, session);

        valkey.advance(Duration.ofSeconds(59));
        MvcResult early = sendLoginOtp(session);
        valkey.advance(Duration.ofSeconds(1));
        MvcResult onTime = sendLoginOtp(session);

        assertThat(early.getResponse().getStatus()).as("AC-20 59 秒: %s", body(early)).isEqualTo(429);
        assertThat(errorCode(early)).isEqualTo("AUTH_076");
        assertThat(retryAfter(early)).as("AC-20 Retry-After（残り秒数）").isNotNull();
        assertThat(Integer.parseInt(retryAfter(early))).as("AC-20 残り秒数").isBetween(1, 2);
        assertThat(onTime.getResponse().getStatus()).as("AC-20 60 秒: %s", body(onTime)).isEqualTo(200);
        awaitOtpOutboxRows(u.userId(), 2);
        assertThat(otpOutboxCount(u.userId())).as("AC-20 待ち中の再送は enqueue しない").isEqualTo(2);
    }

    /** AC-20: 直後の再送は 429 で Retry-After は 60 秒に近い値。 */
    @Test
    @DisplayName("AC-20 直後の再送は429でRetry-Afterは60秒以内")
    void 直後の再送は429() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        sendLoginOtpAndGetCode(u, session);

        MvcResult r = sendLoginOtp(session);

        assertThat(r.getResponse().getStatus()).as("AC-20: %s", body(r)).isEqualTo(429);
        assertThat(errorCode(r)).isEqualTo("AUTH_076");
        assertThat(Integer.parseInt(retryAfter(r))).as("AC-20 Retry-After").isBetween(55, 60);
    }

    /**
     * AC-20 境界: 同一 MFA セッションからの送信は 5 回まで（5 回目は成功）、6 回目は 429（AUTH_077）。
     * 上限超過は outbox 行を増やさない。
     */
    @Test
    @DisplayName("AC-20 境界 セッション送信は5回まで・6回目は429")
    void セッション送信は五回まで() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < SESSION_SEND_LIMIT; i++) {
            if (i > 0) {
                valkey.advance(RESEND_WAIT);
            }
            statuses.add(sendLoginOtp(session).getResponse().getStatus());
        }
        valkey.advance(RESEND_WAIT);

        MvcResult sixth = sendLoginOtp(session);

        assertThat(statuses).as("AC-20 1〜5 回目は送信できる").containsOnly(200);
        assertThat(sixth.getResponse().getStatus()).as("AC-20 6 回目: %s", body(sixth)).isEqualTo(429);
        assertThat(errorCode(sixth)).isEqualTo("AUTH_077");
        awaitOtpOutboxRows(u.userId(), SESSION_SEND_LIMIT);
        Thread.sleep(300);
        assertThat(otpOutboxCount(u.userId())).as("AC-20 上限超過は enqueue しない").isEqualTo(SESSION_SEND_LIMIT);
    }

    /**
     * AC-20 境界: ユーザー単位の送信は 1 時間に 10 回まで。セッションを作り直しても回避できない
     * （セッション A で 5 回・B で 5 回の計 10 回は成功、C の 1 回目は 429 AUTH_077）。1 時間経過で再び送れる。
     * セッションごとに送信元 IP を変え、IP 上限ではなくユーザー上限で止まることを確かめる。
     */
    @Test
    @DisplayName("AC-20 境界 ユーザー単位は1時間10回まで・セッション作り直しで回避不可・1時間後に解除")
    void ユーザー単位は一時間十回まで() throws Exception {
        MfaUser u = totpUser();
        List<Integer> statuses = new ArrayList<>();
        for (int s = 0; s < USER_SEND_LIMIT_PER_HOUR / SESSION_SEND_LIMIT; s++) {
            String session = loginForMfaSession(u);
            String sessionIp = freshIp();
            for (int i = 0; i < SESSION_SEND_LIMIT; i++) {
                statuses.add(sendLoginOtp(session, sessionIp).getResponse().getStatus());
                valkey.advance(RESEND_WAIT);
            }
        }
        String sessionC = loginForMfaSession(u);

        MvcResult eleventh = sendLoginOtp(sessionC, freshIp());

        assertThat(statuses).as("AC-20 1〜10 回目は送信できる").containsOnly(200);
        assertThat(eleventh.getResponse().getStatus()).as("AC-20 11 回目: %s", body(eleventh)).isEqualTo(429);
        assertThat(errorCode(eleventh)).isEqualTo("AUTH_077");

        valkey.advance(Duration.ofHours(1));
        String sessionD = loginForMfaSession(u);
        MvcResult afterHour = sendLoginOtp(sessionD, freshIp());
        assertThat(afterHour.getResponse().getStatus()).as("AC-20 1 時間経過後は送れる: %s", body(afterHour))
                .isEqualTo(200);
    }

    /**
     * AC-20: IP 上限は有効なセッション（別ユーザー）からの送信にも効く。同一 IP から別々のユーザーで送り続けると
     * 30 回以内に 429 になり、別 IP は影響を受けない（無効トークンでの計上は {@code MfaEmailSendPublicEndpointIT} が見る）。
     */
    @Test
    @DisplayName("AC-20 同一IPからの別ユーザー送信も30回以内に429・別IPは影響なし")
    void IP上限は別ユーザーにも効く() throws Exception {
        int ceiling = 30;
        int firstLimited = -1;
        for (int i = 1; i <= ceiling + 1; i++) {
            MfaUser u = lightTotpUser();
            MvcResult r = sendLoginOtp(loginForMfaSession(u), ip);
            if (r.getResponse().getStatus() == 429) {
                firstLimited = i;
                break;
            }
            assertThat(r.getResponse().getStatus()).as("AC-20 上限前は送信できる（%d 回目）: %s", i, body(r))
                    .isEqualTo(200);
        }

        assertThat(firstLimited).as("AC-20 同一 IP から %d 回以内に 429", ceiling).isBetween(2, ceiling);
        MfaUser other = lightTotpUser();
        MvcResult otherIp = sendLoginOtp(loginForMfaSession(other), freshIp());
        assertThat(otherIp.getResponse().getStatus()).as("AC-20 別 IP は影響なし: %s", body(otherIp)).isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-21 再送で旧コード無効
    // ═════════════════════════════════════════════════════════════

    /** AC-21: 再送すると前回のコードは無効（AUTH_074）になり、新しいコードだけが通る。 */
    @Test
    @DisplayName("AC-21 再送で旧コードは無効・新コードは有効")
    void 再送で旧コード無効() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String first = sendLoginOtpAndGetCode(u, session);
        valkey.advance(RESEND_WAIT);
        String second = sendLoginOtpAndGetCode(u, session);
        assumeThat(second).as("偶然同じコードが出た場合は判定不能").isNotEqualTo(first);

        MvcResult old = validate(session, "EMAIL", first);
        MvcResult fresh = validate(session, "EMAIL", second);

        assertThat(old.getResponse().getStatus()).as("AC-21 旧コード: %s", body(old)).isEqualTo(400);
        assertThat(errorCode(old)).isEqualTo("AUTH_074");
        assertThat(fresh.getResponse().getStatus()).as("AC-21 新コード: %s", body(fresh)).isEqualTo(200);
    }

    /** AC-21: 再送後も Valkey のログイン用 OTP キーはセッションにつき 1 つだけ（旧コードの残骸を残さない）。 */
    @Test
    @DisplayName("AC-21 再送後もログイン用OTPキーはセッションにつき1つ")
    void 再送後もキーは一つ() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        sendLoginOtpAndGetCode(u, session);
        valkey.advance(RESEND_WAIT);

        sendLoginOtpAndGetCode(u, session);

        assertThat(valkey.liveKeysMatching(LOGIN_OTP_KEY_MARK)).as("AC-21 ログイン用 OTP キーは 1 つ").hasSize(1);
    }
}
