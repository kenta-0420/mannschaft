package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.MfaRecoveryRequestedEvent;
import com.mannschaft.app.common.BusinessException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261012-0910 試練: 2FA 回復フロー（AC-39・AC-48・AC-52）。
 *
 * <h2>このテストが守る欠陥</h2>
 * <ul>
 *   <li>{@code MfaRecoveryRequestedEvent} を受けるメールリスナーが無く、回復メールが届かない（AC-39）。</li>
 *   <li>回復を再要求しても旧トークンが生きたまま／無効化・再設定をまたいで古い回復トークンが使える／
 *       同一トークンの並列確認で二重にログインできる（AC-48）。</li>
 *   <li>メールのリンクから（MFA セッションも認証も無い別ブラウザで）開いて確認してもログインが完了しない、
 *       完了の監査・通知が無い、秘密鍵が残る（AC-52）。</li>
 * </ul>
 *
 * <p>回復トークンは <b>email_outbox の回復メール本文変数</b>から取り出す（DB の token_hash からは平文を戻せない＝
 * 実際に利用者へ届く経路でしか入手できないことも同時に検証している）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA 回復フロー（AC-39・AC-48・AC-52）")
class MfaRecoveryFlowIT extends MfaDisableRecoveryItSupport {

    /** 先行 TX を commit させずに待たせる時間（後続が行ロックで待たされることの観測窓）。 */
    private static final Duration HOLD = Duration.ofSeconds(3);

    @Autowired
    private ApplicationContext applicationContext;

    // ═════════════════════════════════════════════════════════════
    // AC-39 回復メールの実 enqueue
    // ═════════════════════════════════════════════════════════════

    /** AC-39: 回復要求で MFA_RECOVERY が本人宛に enqueue され、本文変数に回復トークン（リンク）がある。 */
    @Test
    @DisplayName("AC-39 回復要求で本人宛の回復メールがトークン付きでenqueueされる")
    void 回復要求で回復メールがenqueueされる() throws Exception {
        MfaUser u = createTotpUser();

        String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));

        assertThat(token).as("AC-39 回復トークン").isNotBlank();
        assertThat(outboxRecipients(u.userId(), "MFA_RECOVERY")).as("AC-39 宛先は本人").containsOnly(u.email());
        awaitAudit(u.userId(), "MFA_RECOVERY_REQUESTED", 1);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-48 旧トークンの失効・並列確認
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-48 旧回復トークンの失効と並列確認")
    class TokenInvalidation {

        /** AC-48: 回復を再要求すると旧トークンは使えない（新トークンだけが有効）。 */
        @Test
        @DisplayName("AC-48 再要求で旧トークンは失効し新トークンのみ有効")
        void 再要求で旧トークンは失効する() throws Exception {
            MfaUser u = createTotpUser();
            String session = loginForMfaSession(u);
            String first = requestRecoveryAndReadToken(u, session);
            String second = requestRecoveryAndReadToken(u, session);
            assertThat(second).as("前提: 別トークン").isNotEqualTo(first);

            MvcResult old = confirmRecovery(first);

            assertThat(old.getResponse().getStatus()).as("AC-48 旧トークン拒否: %s", body(old)).isEqualTo(400);
            assertThat(errorCode(old)).isEqualTo("AUTH_023");
            assertThat(twoFactorEnabled(u.userId())).as("AC-48 2FA は有効のまま").isTrue();

            MvcResult fresh = confirmRecovery(second);
            assertThat(fresh.getResponse().getStatus()).as("AC-48 新トークンは有効: %s", body(fresh)).isEqualTo(200);
        }

        /** AC-48: 回復要求の後に本人が 2FA を無効化したら、旧回復トークンは使えない（トークンも発行されない）。 */
        @Test
        @DisplayName("AC-48 無効化後の旧回復トークンは拒否されログイン不成立")
        void 無効化後の旧回復トークンは拒否() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            MvcResult d = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));
            assertThat(d.getResponse().getStatus()).as("前提: 無効化: %s", body(d)).isEqualTo(200);
            int refreshBefore = refreshTokenCount(u.userId());

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-48: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_023");
            assertThat(cookieValue(r, "access_token")).as("AC-48 ログイン Cookie を出さない").isNullOrEmpty();
            assertThat(refreshTokenCount(u.userId())).as("AC-48 トークン発行なし").isEqualTo(refreshBefore);
        }

        /** AC-48: 無効化→TOTP 再設定の後に旧回復トークンを使っても、新しい 2FA は外れない。 */
        @Test
        @DisplayName("AC-48 再設定後の旧回復トークンは拒否され新しい2FAは有効のまま")
        void 再設定後の旧回復トークンは拒否() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            MvcResult d = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));
            assertThat(d.getResponse().getStatus()).as("前提: 無効化: %s", body(d)).isEqualTo(200);
            String newSecret = setupTotpViaApi(u.userId());
            MvcResult v = verifyTotpViaApi(u.userId(), currentTotp(newSecret));
            assertThat(v.getResponse().getStatus()).as("前提: 再設定: %s", body(v)).isEqualTo(200);
            int refreshBefore = refreshTokenCount(u.userId());

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-48: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_023");
            assertThat(twoFactorEnabled(u.userId())).as("AC-48 再設定した 2FA は有効のまま").isTrue();
            assertThat(refreshTokenCount(u.userId())).as("AC-48 トークン発行なし").isEqualTo(refreshBefore);
        }

        /**
         * AC-48（Codex 検分指摘）: 回復の再要求が並列に来ても、有効な（未使用の）回復トークンは 1 本だけ。
         *
         * <p>競合の順序を固定する: 先行の再要求が新トークンを書いて回復メールのイベントを出した瞬間（commit 前）に、
         * 後続の再要求を別スレッド（別 TX）で開始し、先行をしばらく commit させない（{@link ConcurrentOnEvent}）。</p>
         * <ul>
         *   <li>直列化が無い実装: 後続は先行の未 commit のトークンを見ずに失効対象「なし」と読み、先行の commit 前に
         *       自分のトークンを発行して終わる → 未使用 2 本。</li>
         *   <li>行ロック前に通常 SELECT（ユーザー取得等）を打つ実装: 後続の読取りビューがロック待ちの前に固定され、
         *       先行の commit 後もその通常 SELECT からは先行のトークンが見えず、失効し損ねる → 未使用 2 本。</li>
         * </ul>
         * <p>正しい実装（最初の DB 読取りを 2FA 行ロックにし、失効は一括 UPDATE＝current read）では、後続は先行の
         * commit まで待たされ、先行のトークンを失効させてから自分のトークンを発行する → 未使用 1 本。</p>
         */
        @Test
        @DisplayName("AC-48 回復の再要求が先行のcommit待ちで並んでも有効な回復トークンは1本")
        void 回復の再要求の並列2件でも有効なトークンは1本() throws Exception {
            MfaUser u = createTotpUser();
            String session = loginForMfaSession(u);

            try (ConcurrentOnEvent race = ConcurrentOnEvent.arm(applicationContext, MfaRecoveryRequestedEvent.class,
                    () -> auth2faService.requestMfaRecovery(session), HOLD)) {
                auth2faService.requestMfaRecovery(session);

                assertThat(race.fired()).as("前提: 先行の commit 前に後続を開始した").isTrue();
                assertThat(race.completedWhileHeld()).as("後続は先行の 2FA 行ロックで待たされる（直列化）").isFalse();
                race.follower().get(60, TimeUnit.SECONDS);
            }

            Integer unused = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mfa_recovery_tokens WHERE user_id = ? AND used_at IS NULL",
                    Integer.class, u.userId());
            assertThat(unused).as("AC-48 有効な回復トークンは最新の 1 本だけ").isEqualTo(1);
        }

        /**
         * 回復確認の全セッション失効（Codex 検分指摘 P1）: 回復確認が 2FA 行ロックを待っている間に、別 TX のログイン
         * （2FA 検証）が Refresh Token を発行して commit しても、そのトークンも失効させる。
         *
         * <p>競合の順序を固定する: 先行の 2FA ログイン検証が Refresh Token を発行して LoginSuccessEvent を出した瞬間
         * （commit 前・2FA 行をロック中）に回復確認を別スレッドで開始する。回復確認はトークン所有者を通常 SELECT で引いた
         * （＝読取りビューが固定された）後に 2FA 行ロックで待たされ、先行の commit 後に進む。失効対象を通常 SELECT で
         * 列挙する実装では、固定済みのビューから先行のトークンが見えず生き残る。</p>
         */
        @Test
        @DisplayName("回復確認のロック待ち中に別TXが発行・commitしたRefresh Tokenも失効させる")
        void 回復確認はロック待ち中に発行されたRefreshTokenも失効させる() throws Exception {
            MfaUser u = createTotpUser();
            String recoveryToken = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            String loginSession = loginForMfaSession(u);
            assertThat(activeRefreshTokenCount(u.userId())).as("前提: 有効な Refresh Token なし").isZero();

            try (ConcurrentOnEvent race = ConcurrentOnEvent.arm(applicationContext, LoginSuccessEvent.class,
                    () -> auth2faService.confirmMfaRecovery(recoveryToken), HOLD)) {
                auth2faService.validateMfaLogin(loginSession, MfaVerificationMethod.BACKUP_CODE,
                        u.backupCodes().get(0), "203.0.113.20", USER_AGENT);

                assertThat(race.fired()).as("前提: ログインの commit 前に回復確認を開始した").isTrue();
                assertThat(race.completedWhileHeld()).as("回復確認はログイン側の 2FA 行ロックで待たされる").isFalse();
                race.follower().get(60, TimeUnit.SECONDS);
            }

            assertThat(refreshTokenCount(u.userId())).as("前提: ログイン分と回復確認分の 2 本が発行された").isEqualTo(2);
            assertThat(activeRefreshTokenCount(u.userId()))
                    .as("ロック待ち中に commit されたログインのトークンも失効し、有効なのは回復確認の 1 本だけ").isEqualTo(1);
            assertThat(twoFactorRowCount(u.userId())).as("2FA は外れている").isZero();
        }

        /** AC-48: 同一トークンを 2 スレッドで同時に確認しても成功は 1 件、発行トークンも 1 組。 */
        @Test
        @DisplayName("AC-48 同一回復トークンの並列確認は成功1件")
        void 同一回復トークンの並列確認は成功1件() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            int refreshBefore = refreshTokenCount(u.userId());

            List<String> failures = new ArrayList<>();
            int successes = runConcurrently(
                    () -> auth2faService.confirmMfaRecovery(token),
                    () -> auth2faService.confirmMfaRecovery(token),
                    failures);

            assertThat(successes).as("AC-48 成功は 1 件だけ（失敗: %s）", failures).isEqualTo(1);
            assertThat(failures).as("AC-48 負けた側は無効トークン扱い").containsExactly("AUTH_023");
            assertThat(refreshTokenCount(u.userId())).as("AC-48 発行トークンは 1 組").isEqualTo(refreshBefore + 1);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-52 メールリンクからの確認でログイン完了
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-52 メールリンクからの回復確認")
    class ConfirmFromEmailLink {

        /**
         * AC-52: MFA セッションが消えた（別ブラウザでメールを開いた）状態でも、リンクのトークンだけで確認でき、
         * アクセス／リフレッシュ Cookie が発行され、その Cookie で認証済み API に到達できる。
         */
        @Test
        @DisplayName("AC-52 MFAセッション無しでもリンクのトークンで確認しCookie発行されログイン完了")
        void セッション無しでも確認しログイン完了() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            valkey.liveKeysMatching("^" + Pattern.quote(MFA_SESSION_KEY_PREFIX))
                    .forEach(k -> redisTemplate.delete(k));
            int refreshBefore = refreshTokenCount(u.userId());

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-52: %s", body(r)).isEqualTo(200);
            String access = cookieValue(r, "access_token");
            assertThat(access).as("AC-52 access_token Cookie").isNotBlank();
            assertThat(cookieValue(r, "refresh_token")).as("AC-52 refresh_token Cookie").isNotBlank();
            assertThat(refreshTokenCount(u.userId())).as("AC-52 リフレッシュトークン 1 組").isEqualTo(refreshBefore + 1);

            MvcResult st = mockMvc.perform(get(STATUS_PATH).servletPath(STATUS_PATH).cookie(new Cookie("access_token", access)))
                    .andReturn();
            assertThat(st.getResponse().getStatus()).as("AC-52 発行 Cookie で認証できる: %s", body(st)).isEqualTo(200);
            assertThat(json(st).path("data").path("enabled").asBoolean(true)).as("AC-52 2FA は無効").isFalse();
        }

        /** AC-52: 回復完了で秘密鍵・バックアップコードを残さない（従来は is_enabled=false のみで秘密鍵が残存）。 */
        @Test
        @DisplayName("AC-52 回復完了で秘密鍵とバックアップコードを残さない")
        void 回復完了で秘密鍵を残さない() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-52 前提: %s", body(r)).isEqualTo(200);
            assertThat(residualSecretRowCount(u.userId())).as("AC-52 秘密鍵・有効フラグ・コードが残らない").isZero();
        }

        /** AC-52: 期限切れトークンは 400 AUTH_023・Cookie なし・2FA は有効のまま。 */
        @Test
        @DisplayName("AC-52 期限切れトークンは拒否されログイン不成立")
        void 期限切れトークンは拒否() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            jdbcTemplate.update("UPDATE mfa_recovery_tokens SET expires_at = ? WHERE user_id = ?",
                    LocalDateTime.now().minusDays(1), u.userId());

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-52: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_023");
            assertThat(cookieValue(r, "access_token")).as("AC-52 Cookie なし").isNullOrEmpty();
            assertThat(twoFactorEnabled(u.userId())).as("AC-52 2FA は有効のまま").isTrue();
        }

        /** AC-52: 使用済みトークンの再使用は 400 AUTH_023・Cookie なし。 */
        @Test
        @DisplayName("AC-52 使用済みトークンの再使用は拒否")
        void 使用済みトークンは拒否() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));
            MvcResult first = confirmRecovery(token);
            assertThat(first.getResponse().getStatus()).as("前提: 1 回目: %s", body(first)).isEqualTo(200);
            int refreshBefore = refreshTokenCount(u.userId());

            MvcResult again = confirmRecovery(token);

            assertThat(again.getResponse().getStatus()).as("AC-52: %s", body(again)).isEqualTo(400);
            assertThat(errorCode(again)).isEqualTo("AUTH_023");
            assertThat(cookieValue(again, "access_token")).as("AC-52 Cookie なし").isNullOrEmpty();
            assertThat(refreshTokenCount(u.userId())).as("AC-52 トークン発行なし").isEqualTo(refreshBefore);
        }

        /** AC-52/AC-35: 完了監査 MFA_RECOVERY_COMPLETED と MFA_DISABLED（reason=RECOVERY）が残る。 */
        @Test
        @DisplayName("AC-52 AC-35 完了監査MFA_RECOVERY_COMPLETEDとMFA_DISABLED(reason=RECOVERY)が残る")
        void 完了監査が残る() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-52 前提: %s", body(r)).isEqualTo(200);
            awaitAudit(u.userId(), "MFA_RECOVERY_COMPLETED", 1);
            awaitAudit(u.userId(), "MFA_DISABLED", 1);
            assertThat(latestAuditMetadata(u.userId(), "MFA_DISABLED", "$.reason")).as("AC-35 reason")
                    .isEqualTo("RECOVERY");
        }

        /** AC-52/AC-37: 本人宛に「2FA が無効化された」セキュリティ通知が出る。 */
        @Test
        @DisplayName("AC-52 AC-37 回復完了で本人宛のセキュリティ通知メールが出る")
        void 回復完了で通知メールが出る() throws Exception {
            MfaUser u = createTotpUser();
            String token = requestRecoveryAndReadToken(u, loginForMfaSession(u));

            MvcResult r = confirmRecovery(token);

            assertThat(r.getResponse().getStatus()).as("AC-52 前提: %s", body(r)).isEqualTo(200);
            List<Map<String, String>> payloads = awaitOutboxPayloads(u.userId(), "MFA_SECURITY_NOTICE", 1);
            assertThat(outboxRecipients(u.userId(), "MFA_SECURITY_NOTICE")).as("AC-37 宛先は本人")
                    .containsOnly(u.email());
            payloads.forEach(p -> p.values().forEach(v ->
                    assertThat(v == null ? "" : v).as("AC-52 通知に回復トークンを載せない").doesNotContain(token)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 並列実行（実 2 スレッド・開始を揃える）
    // ─────────────────────────────────────────────────────────────

    private static int runConcurrently(Callable<?> a, Callable<?> b, List<String> failureCodes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> c : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return c.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            int successes = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof BusinessException be) {
                        failureCodes.add(be.getErrorCode().getCode());
                    } else {
                        failureCodes.add(String.valueOf(cause));
                    }
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }
}
