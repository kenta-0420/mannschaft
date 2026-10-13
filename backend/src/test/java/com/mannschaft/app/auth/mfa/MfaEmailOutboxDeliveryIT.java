package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.event.MfaEmailOtpRequestedEvent;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.mail.outbox.EmailOutboxWorker;
import com.mannschaft.app.mail.outbox.EmailTransport;
import net.javacrumbs.shedlock.core.LockAssert;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: メールOTP が outbox を経て実際に「送られる」こと（AC-50）と、その本文・ログの契約（AC-26）。
 *
 * <h2>なぜ Worker まで通すのか</h2>
 * <p>{@code EmailTemplateKind.MFA_EMAIL_OTP} は骨格で enum に足しただけで、{@code EmailOutboxServiceImpl#renderTemplate}
 * の switch に分岐が無い（{@code default -> IllegalStateException}）。enqueue だけを見るテストは緑のまま、
 * 実運用では全件がバックオフ→DEAD_LETTER になりメールが 1 通も届かない。本クラスは
 * <b>実 {@link EmailOutboxWorker#poll()} → 実 {@code processOne} → 実レンダリング</b>まで通し、行が SENT になることと、
 * 送信境界に渡った宛先・件名・本文を確かめる。</p>
 *
 * <h2>モックの範囲</h2>
 * <p>差し替えるのは外部境界の {@link EmailTransport}（test プロファイルでは既に SES を呼ばない
 * {@code LoggingEmailTransport}）だけ。TestContext を分岐させないよう {@code @MockitoBean} は使わず、
 * 実 {@code EmailOutboxServiceImpl} の送信境界フィールドを捕捉用実装に一時的に差し替え、{@link AfterEach} で戻す。
 * DB・暗号化・レンダラ・Worker は実物。test プロファイルは {@code @EnableScheduling} が無効なので、
 * Worker は ShedLock の {@link LockAssert.TestHelper} で表明を通して同期で呼ぶ。</p>
 *
 * <h2>enqueue 失敗時の期待仕様（AC-50。本テストで定義する）</h2>
 * <p><b>enqueue（email_outbox への登録）が失敗したら、その送信要求は「無かったこと」にする</b>:
 * <ul>
 *   <li>API は成功（200）を返さない（届かないメールを「送った」と偽らない。障害対応の原則）</li>
 *   <li>セッション送信回数・ユーザー送信回数（AC-20 の送信枠）を消費しない</li>
 *   <li>再送待ち（60 秒）を課さない — 原因が解消すれば<b>直ちに</b>再送できる</li>
 * </ul>
 * したがって enqueue は送信 API のトランザクション内で同期に行い（{@code @Async} の AFTER_COMMIT では失敗を
 * 呼び出し元が知り得ない）、失敗時は Valkey 側の枠・待ちの記録も取り消す必要がある。
 * 失敗は「outbox が受け付けない宛先」（TLD の無いアドレス。{@code EMAIL_OUTBOX_001}）で実際に起こす。</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 メールOTP の配送と本文・ログ（AC-26/50）")
class MfaEmailOutboxDeliveryIT extends MfaEmailOtpItSupport {

    @Autowired
    private EmailOutboxService emailOutboxService;
    @Autowired
    private EmailOutboxWorker emailOutboxWorker;

    private Object outboxTarget;
    private EmailTransport originalTransport;

    /** 送信境界（外部）の捕捉用実装。 */
    static final class CapturingTransport implements EmailTransport {
        record Sent(String to, String subject, String html) {
        }

        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public String send(String toAddress, String subject, String htmlBody) {
            sent.add(new Sent(toAddress, subject, htmlBody));
            return "CAPTURED-" + UUID.randomUUID();
        }

        List<Sent> to(String address) {
            return sent.stream().filter(s -> address.equals(s.to())).toList();
        }
    }

    private CapturingTransport captureTransport() {
        outboxTarget = AopTestUtils.getUltimateTargetObject(emailOutboxService);
        originalTransport = (EmailTransport) ReflectionTestUtils.getField(outboxTarget, "emailTransport");
        CapturingTransport capture = new CapturingTransport();
        ReflectionTestUtils.setField(outboxTarget, "emailTransport", capture);
        return capture;
    }

    @AfterEach
    void restoreTransport() {
        if (outboxTarget != null && originalTransport != null) {
            ReflectionTestUtils.setField(outboxTarget, "emailTransport", originalTransport);
        }
        outboxTarget = null;
        originalTransport = null;
    }

    /** 実 Worker を、指定行が PENDING を抜けるまで同期で回す（1 回 50 件。他テストの残り行があっても届くまで）。 */
    private void drainWorkerUntilProcessed(UUID outboxId) {
        LockAssert.TestHelper.makeAllAssertsPass(true);
        try {
            // 行の next_attempt_at は enqueue 側 JVM の壁時計（EmailOutboxEntity#prepareForEnqueue）で、取得条件は DB の NOW(3)。
            // JVM と DB コンテナ（WSL2 等）の時計差で、投入直後の数百 ms は「まだ対象外」になりうるため、
            // PENDING を抜けるまで Worker を回し直す（TEST_CONVENTION: 待機は Thread.sleep ではなく Awaitility）。
            Awaitility.await("outbox 行が Worker 処理で PENDING を抜ける")
                    .atMost(Duration.ofSeconds(10))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> {
                        if ("PENDING".equals(outboxStatus(outboxId))) {
                            emailOutboxWorker.poll();
                        }
                        return !"PENDING".equals(outboxStatus(outboxId));
                    });
        } finally {
            LockAssert.TestHelper.makeAllAssertsPass(false);
        }
    }

    private String outboxStatus(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return jdbcTemplate.queryForObject("SELECT status FROM email_outbox WHERE id = ?", String.class, bb.array());
    }

    private static boolean containsCode(String text, String code) {
        return Pattern.compile("(?<![0-9A-Za-z])" + code + "(?![0-9A-Za-z])").matcher(text).find();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-50 Worker 処理後に SENT・宛先と本文
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-50: ログイン用メールOTP の outbox 行は Worker 処理後に SENT になり、送信境界には本人宛・件名あり・
     * 本文にコードを含むメールがちょうど 1 通渡る。
     */
    @Test
    @DisplayName("AC-50 ログイン用OTPはWorker処理後にSENT・本人宛にコード入りで1通")
    void ログイン用OTPがSENTになる() throws Exception {
        CapturingTransport capture = captureTransport();
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        UUID rowId = otpOutboxRows(u.userId()).get(0).id();

        drainWorkerUntilProcessed(rowId);

        assertThat(outboxStatus(rowId)).as("AC-50 Worker 処理後は SENT").isEqualTo("SENT");
        List<CapturingTransport.Sent> mails = capture.to(u.email());
        assertThat(mails).as("AC-50 本人宛に 1 通").hasSize(1);
        assertThat(mails.get(0).subject()).as("AC-50 件名").isNotBlank();
        assertThat(containsCode(mails.get(0).html(), code)).as("AC-50 本文にコード").isTrue();
    }

    /** AC-50: 設定用・本人確認用の OTP も同じく Worker 処理後に SENT・本人宛にコード入りで届く。 */
    @Test
    @DisplayName("AC-50 設定用・本人確認用OTPもWorker処理後にSENT・本人宛")
    void 設定用と本人確認用もSENTになる() throws Exception {
        CapturingTransport capture = captureTransport();
        long plain = plainUser();
        String plainEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, plain);
        String setupCode = startSetupAndGetCode(plain);
        MfaUser u = totpUser();
        String reauthCode = sendReauthAndGetCode(u.userId());
        UUID setupRow = otpOutboxRows(plain).get(0).id();
        UUID reauthRow = otpOutboxRows(u.userId()).get(0).id();

        drainWorkerUntilProcessed(setupRow);
        drainWorkerUntilProcessed(reauthRow);

        assertThat(outboxStatus(setupRow)).as("AC-50 設定用 SENT").isEqualTo("SENT");
        assertThat(outboxStatus(reauthRow)).as("AC-50 本人確認用 SENT").isEqualTo("SENT");
        assertThat(capture.to(plainEmail)).hasSize(1)
                .allSatisfy(m -> assertThat(containsCode(m.html(), setupCode)).as("AC-50 設定用コード").isTrue());
        assertThat(capture.to(u.email())).hasSize(1)
                .allSatisfy(m -> assertThat(containsCode(m.html(), reauthCode)).as("AC-50 本人確認用コード").isTrue());
    }

    /**
     * AC-50（期待仕様）: enqueue が失敗した送信要求は 200 を返さず、送信枠を消費せず、再送待ちも課さない。
     * 原因（宛先）が直れば待たずに再送でき、その後もセッション送信枠は満額（5 回）使える。
     */
    @Test
    @DisplayName("AC-50 enqueue失敗は200を返さず送信枠・再送待ちを消費せず直ちに再送できる")
    void enqueue失敗は枠も待ちも消費しない() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        updateEmail(u.userId(), "mfa-it-" + UUID.randomUUID() + "@localhost"); // outbox が拒否する宛先（TLD なし）

        MvcResult failed = sendLoginOtp(session);

        assertThat(failed.getResponse().getStatus()).as("AC-50 enqueue 失敗を成功と偽らない: %s", body(failed))
                .isNotEqualTo(200).isNotEqualTo(429);
        // 失敗した要求から遅れて行が現れないことを一定時間確かめる（Thread.sleep ではなく Awaitility の during）
        Awaitility.await("AC-50 失敗した送信要求の outbox 行は現れない")
                .during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(2))
                .until(() -> otpOutboxCount(u.userId()) == 0);
        assertThat(otpOutboxCount(u.userId())).as("AC-50 行は作られていない").isZero();

        String fixed = "mfa-it-" + UUID.randomUUID() + "@example.com";
        updateEmail(u.userId(), fixed);
        MvcResult retry = sendLoginOtp(session);
        assertThat(retry.getResponse().getStatus()).as("AC-50 再送待ちを課さず直ちに再送できる: %s", body(retry))
                .isEqualTo(200);
        for (int i = 1; i < SESSION_SEND_LIMIT; i++) {
            valkey.advance(RESEND_WAIT);
            MvcResult r = sendLoginOtp(session);
            assertThat(r.getResponse().getStatus()).as("AC-50 失敗分は枠を消費しない（成功 %d 回目）: %s", i + 1, body(r))
                    .isEqualTo(200);
        }
        valkey.advance(RESEND_WAIT);
        MvcResult over = sendLoginOtp(session);
        assertThat(over.getResponse().getStatus()).as("AC-50 成功 5 回の後は上限: %s", body(over)).isEqualTo(429);
        assertThat(errorCode(over)).isEqualTo("AUTH_077");
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(u.userId(), SESSION_SEND_LIMIT);
        assertThat(rows).extracting(OtpOutboxRow::toAddress).as("AC-50 修正後の宛先にだけ送る").containsOnly(fixed);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-26 本文・冪等キー・ログ
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-26: 本文はコード・有効期限（10 分）・「心当たりが無ければパスワードを変更」の案内を含み、
     * ログインリンクを含まない（フィッシング誘導の型を作らない）。MFA セッショントークンも含まない。
     */
    @Test
    @DisplayName("AC-26 本文はコード・10分・PW変更案内を含みログインリンクとトークンを含まない")
    void 本文の契約() throws Exception {
        CapturingTransport capture = captureTransport();
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        String code = sendLoginOtpAndGetCode(u, session);
        drainWorkerUntilProcessed(otpOutboxRows(u.userId()).get(0).id());

        List<CapturingTransport.Sent> mails = capture.to(u.email());
        assertThat(mails).hasSize(1);
        String html = mails.get(0).html();
        assertThat(containsCode(html, code)).as("AC-26 コード").isTrue();
        assertThat(html).as("AC-26 有効期限（10 分）").contains("10分");
        assertThat(html).as("AC-26 心当たりが無ければパスワード変更の案内").contains("パスワード");
        assertThat(html).as("AC-26 ログインリンクを含まない").doesNotContain("/login");
        assertThat(html).as("AC-26 MFA セッショントークンを含まない").doesNotContain(session);
        assertThat(html).as("AC-26 トークン付き URL を含まない").doesNotContain("token=");
        assertThat(mails.get(0).subject()).as("AC-26 件名にコードを載せない（通知プレビュー漏えい防止）")
                .doesNotContain(code);
    }

    /**
     * AC-26: sourceEvent は送信ごとに一意。同一セッションで 2 回送ると outbox 行は 2 件で、
     * source_event_id・idempotency_key がともに非 null かつ異なる（冪等キー衝突で 2 通目が落ちない）。
     */
    @Test
    @DisplayName("AC-26 sourceEventIdは送信ごとに一意で2回送れば2行")
    void sourceEventIdは送信ごとに一意() throws Exception {
        MfaUser u = totpUser();
        String session = loginForMfaSession(u);
        sendLoginOtpAndGetCode(u, session);
        valkey.advance(RESEND_WAIT);
        sendLoginOtpAndGetCode(u, session);

        List<OtpOutboxRow> rows = otpOutboxRows(u.userId());

        assertThat(rows).as("AC-26 2 行").hasSize(2);
        assertThat(rows).extracting(OtpOutboxRow::sourceEventId).as("AC-26 source_event_id 非 null").doesNotContainNull();
        assertThat(rows.get(0).sourceEventId()).as("AC-26 source_event_id は送信ごとに一意")
                .isNotEqualTo(rows.get(1).sourceEventId());
        assertThat(rows.get(0).idempotencyKey()).as("AC-26 idempotency_key も別")
                .isNotEqualTo(rows.get(1).idempotencyKey());
    }

    /**
     * AC-26: 送信 API・非同期リスナー・Worker の一連の処理でコードをログに出さない。
     * キャプチャが効いていることは Worker の送信ログ（{@code [EMAIL_OUTBOX][SIMULATE]}）が取れていることで確かめる
     * （何も捕捉できていないのに「出ていない」と判定する偽 green を防ぐ）。
     */
    @Test
    @DisplayName("AC-26 送信から配送までコードをログに出さない")
    void コードをログに出さない(CapturedOutput output) throws Exception {
        // test プロファイルの logback は root が WARN のため、Worker の送信ログ（INFO）が出ず前提が実行順次第で崩れる。
        // 捕捉対象のロガー（送信ログの LoggingEmailTransport と、コードを出しうる mail / auth 配下）を DEBUG へ上げ、
        // 終了時に必ず元のレベルへ戻す（logback-spring.xml は変えない）。検査（コードが出ないこと）は弱めない。
        List<String> loggerNames = List.of(
                "com.mannschaft.app.mail.outbox.LoggingEmailTransport",
                "com.mannschaft.app.mail",
                "com.mannschaft.app.auth");
        java.util.Map<String, Level> originalLevels = new java.util.LinkedHashMap<>();
        for (String name : loggerNames) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            originalLevels.put(name, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
        String logs;
        String code;
        try {
            MfaUser u = totpUser();
            String session = loginForMfaSession(u);
            code = sendLoginOtpAndGetCode(u, session);
            UUID rowId = otpOutboxRows(u.userId()).get(0).id();
            drainWorkerUntilProcessed(rowId);
            MvcResult ok = validate(session, "EMAIL", code);
            assertThat(ok.getResponse().getStatus()).as("前提: 検証成功: %s", body(ok)).isEqualTo(200);
            logs = output.getAll();
        } finally {
            originalLevels.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        }

        assertThat(logs).as("前提: ログを捕捉できている（Worker の送信ログ）").contains("[EMAIL_OUTBOX][SIMULATE]");
        assertThat(containsCode(logs, code)).as("AC-26 コード %s をログに出さない", code).isFalse();
    }

    /** AC-26: OTP 送信要求イベントの文字列表現（ログに載りうる）にコード・宛先を含めない。 */
    @Test
    @DisplayName("AC-26 MfaEmailOtpRequestedEventの文字列表現にコードと宛先を含めない")
    void イベントの文字列表現にコードを含めない() {
        MfaEmailOtpRequestedEvent ev = new MfaEmailOtpRequestedEvent(
                1L, "someone@example.com", "482913", MfaEmailOtpPurpose.LOGIN, 600, UUID.randomUUID().toString());

        assertThat(ev.toString()).as("AC-26").doesNotContain("482913").doesNotContain("someone@example.com");
    }
}
