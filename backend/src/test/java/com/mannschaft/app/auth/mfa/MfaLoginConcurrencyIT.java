package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.service.MfaSessionService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CMP-261012-0910 試練: 2FA ログイン検証の並列競合（実際に 2 スレッドで同時実行する）。
 *
 * <p>対象 AC:</p>
 * <ul>
 *   <li>AC-6 TOTP 使用済み印の原子化（{@code setIfAbsent}）・TTL ≥ 90 秒。同一コード並列 2 件で成功 1 件</li>
 *   <li>AC-9 同一バックアップコード並列 2 件で成功 1 件（PESSIMISTIC_WRITE）</li>
 *   <li>AC-15 成功時に MFA セッションを原子削除。同一セッション並列成功でもトークンは 1 組</li>
 * </ul>
 *
 * <p>並列実行は Service（{@code Auth2faService#validateMfaLogin}）を直接 2 スレッドから呼ぶ
 * （MockMvc の並行利用を避けるため。認可の掛からない公開フローなので Security を跨ぐ意味は無い）。
 * 「読んでから書く」非原子実装を偶然の通過で見逃さないよう、{@link MfaValkeyFake#armReadBarrier} で
 * 両スレッドが書く前に読む順序を強制する。原子プリミティブで実装されていれば読み取り自体が起きず影響しない。</p>
 *
 * <p>本クラスに {@code @Transactional} は付けない（各スレッドが自前のトランザクションで実 DB を更新し、
 * コミット結果を観測するため）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA ログイン検証の並列競合（AC-6/9/15）")
class MfaLoginConcurrencyIT extends MfaItSupport {

    private static final String IP = "203.0.113.10";

    /** 2 つの処理を同時に開始させ、成功数と失敗（BusinessException）のコードを集める。 */
    private Outcome runConcurrently(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            Outcome outcome = new Outcome();
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    outcome.successes++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof BusinessException be) {
                        outcome.failureCodes.add(be.getErrorCode().getCode());
                    } else {
                        outcome.failureCodes.add(String.valueOf(cause));
                    }
                }
            }
            return outcome;
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class Outcome {
        int successes;
        final List<String> failureCodes = new ArrayList<>();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-6 TOTP 使用済み印
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-6: 同一 TOTP コードを別々の MFA セッションから同時に送ると、成功は 1 件だけ
     * （使用済み印は {@code hasKey → set} ではなく {@code setIfAbsent} で原子的に取る）。
     */
    @Test
    @DisplayName("AC-6 同一TOTPコードの並列2件は成功1件")
    void 同一TOTPコード並列は成功1件() throws Exception {
        MfaUser u = createTotpUser();
        String s1 = loginForMfaSession(u);
        String s2 = loginForMfaSession(u);
        String code = currentTotp(u.totpSecret());
        valkey.armReadBarrier(TOTP_USED_KEY_PREFIX, 2);

        Outcome o = runConcurrently(
                () -> auth2faService.validateMfaLogin(s1, MfaVerificationMethod.TOTP, code, IP, USER_AGENT),
                () -> auth2faService.validateMfaLogin(s2, MfaVerificationMethod.TOTP, code, IP, USER_AGENT));

        assertThat(o.successes).as("AC-6 成功は 1 件だけ（失敗: %s）", o.failureCodes).isEqualTo(1);
        assertThat(o.failureCodes).as("AC-6 負けた側は不正コード扱い").containsExactly("AUTH_074");
        assertThat(refreshTokenCount(u.userId())).as("AC-6 発行トークンは 1 組").isEqualTo(1);
    }

    /**
     * AC-6: TOTP 使用済み印の TTL は 90 秒以上（±1 ウィンドウ＝コードが有効でありうる最大 90 秒をカバー。
     * F01.1 §TOTP コード検証フロー）。現行 60 秒だと、有効期間の終わり 30 秒で同じコードを再利用できる。
     */
    @Test
    @DisplayName("AC-6 TOTP使用済み印のTTLは90秒以上")
    void TOTP使用済み印のTTLは90秒以上() throws Exception {
        MfaUser u = createTotpUser();
        String code = currentTotp(u.totpSecret());

        MvcResult r = validate(loginForMfaSession(u), "TOTP", code);

        assertThat(r.getResponse().getStatus()).as("AC-6 前提: 成功: %s", body(r)).isEqualTo(200);
        String usedKeyRegex = "totp_used.*:" + u.userId() + ":" + code + "$";
        assertThat(valkey.liveKeysMatching(usedKeyRegex)).as("AC-6 使用済み印がある").hasSize(1);
        String key = valkey.liveKeysMatching(usedKeyRegex).iterator().next();
        assertThat(valkey.ttlMillis(key)).as("AC-6 TTL ≥ 90 秒（経過分 2 秒を許容）").isGreaterThanOrEqualTo(88_000L);

        MvcResult replay = validate(loginForMfaSession(u), "TOTP", code);
        assertThat(replay.getResponse().getStatus()).as("AC-6 同一コードの再利用は拒否: %s", body(replay)).isEqualTo(400);
        assertThat(errorCode(replay)).isEqualTo("AUTH_074");
    }

    // ═════════════════════════════════════════════════════════════
    // AC-9 バックアップコード
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-9: 同一バックアップコードを別々の MFA セッションから同時に送ると、成功は 1 件だけ。
     * two_factor_auth 行を PESSIMISTIC_WRITE で取ってから照合・削除する（読んだ JSON の古い写しで両者が通らない）。
     */
    @Test
    @DisplayName("AC-9 同一バックアップコードの並列2件は成功1件")
    void 同一バックアップコード並列は成功1件() throws Exception {
        MfaUser u = createTotpUser();
        String s1 = loginForMfaSession(u);
        String s2 = loginForMfaSession(u);
        String code = u.backupCodes().get(2);

        Outcome o = runConcurrently(
                () -> auth2faService.validateMfaLogin(s1, MfaVerificationMethod.BACKUP_CODE, code, IP, USER_AGENT),
                () -> auth2faService.validateMfaLogin(s2, MfaVerificationMethod.BACKUP_CODE, code, IP, USER_AGENT));

        assertThat(o.successes).as("AC-9 成功は 1 件だけ（失敗: %s）", o.failureCodes).isEqualTo(1);
        assertThat(o.failureCodes).as("AC-9 負けた側は不正コード扱い").containsExactly("AUTH_074");
        assertThat(backupCodeCount(u.userId())).as("AC-9 削除は 1 件").isEqualTo(7);
        assertThat(refreshTokenCount(u.userId())).as("AC-9 発行トークンは 1 組").isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-16/AC-46 ユーザーロックと並列検証（Codex 検分指摘）
    // ═════════════════════════════════════════════════════════════

    /**
     * 失敗 9 回の状態で検証 2 本が並列に走り、B がロック確認を通った直後に A の 10 回目の失敗でロックが掛かったら、
     * B は正しい TOTP を持っていても照合を進めず AUTH_003、トークンも発行されない。
     *
     * <p>B のロック確認（{@code hasKey(account_lock)}）の直後に代役の割り込みで A を<b>別スレッド</b>（別 TX）で
     * 最後まで走らせ、ロックが掛かった状態から B を再開させる。ロック確認が行ロック取得前の 1 回だけだと、
     * B はそのまま照合してトークン発行まで行ける（入口の確認が古い）。</p>
     */
    @Test
    @DisplayName("AC-16 AC-46 並列側の10回目の失敗でロックが掛かったら後続の検証は正しいコードでもAUTH_003")
    void 並列側の10回目の失敗でロック後は後続も通らない() throws Exception {
        MfaUser u = createTotpUser();
        String sessionA = loginForMfaSession(u);
        String sessionB = loginForMfaSession(u);
        valkey.rawSet("mannschaft:auth:mfa_fail_count:" + u.userId(), "9", Duration.ofMinutes(30));
        String lockKey = MfaSessionService.accountLockKey(u.userId());
        String wrong = wrongTotp(u.totpSecret());
        String correct = currentTotp(u.totpSecret());
        List<String> aFailures = new ArrayList<>();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            valkey.armInterleaveOnAccess(lockKey, () -> {
                Future<?> a = other.submit(() -> auth2faService.validateMfaLogin(
                        sessionA, MfaVerificationMethod.TOTP, wrong, IP, USER_AGENT));
                try {
                    a.get(60, TimeUnit.SECONDS);
                    aFailures.add("SUCCESS");
                } catch (ExecutionException ex) {
                    aFailures.add(ex.getCause() instanceof BusinessException be
                            ? be.getErrorCode().getCode() : String.valueOf(ex.getCause()));
                } catch (Exception ex) {
                    aFailures.add(String.valueOf(ex));
                }
            });

            Throwable b = catchThrowable(() -> auth2faService.validateMfaLogin(
                    sessionB, MfaVerificationMethod.TOTP, correct, IP, USER_AGENT));

            assertThat(aFailures).as("前提: A は 10 回目の失敗（AUTH_074）でロックを掛けた").containsExactly("AUTH_074");
            assertThat(valkey.rawGet(lockKey)).as("前提: ロックが掛かっている").isNotNull();
            assertThat(b).as("B はロック後に照合を進めない").isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) b).getErrorCode().getCode()).as("B はロック中").isEqualTo("AUTH_003");
            assertThat(refreshTokenCount(u.userId())).as("トークンは発行されない").isZero();
        } finally {
            other.shutdownNow();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-15 成功時のセッション原子削除
    // ═════════════════════════════════════════════════════════════

    /** AC-15: 成功後、同じ MFA セッションは再利用できない（別の正しい要素でも AUTH_073）。 */
    @Test
    @DisplayName("AC-15 成功後の同一セッション再利用は失敗")
    void 成功後のセッション再利用は失敗() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        MvcResult first = validate(session, "BACKUP_CODE", u.backupCodes().get(0));
        assertThat(first.getResponse().getStatus()).as("AC-15 前提: 成功: %s", body(first)).isEqualTo(200);

        MvcResult reuse = validate(session, "BACKUP_CODE", u.backupCodes().get(1));

        assertThat(reuse.getResponse().getStatus()).as("AC-15 再利用: %s", body(reuse)).isEqualTo(400);
        assertThat(errorCode(reuse)).as("AC-15 セッション無効").isEqualTo("AUTH_073");
        assertThat(backupCodeCount(u.userId())).as("AC-15 2 件目は消費されない").isEqualTo(7);
    }

    /**
     * AC-15: 同一 MFA セッションに別々の正しい要素（バックアップコード 2 件）を同時に送っても、
     * 成功は 1 件・発行トークンは 1 組（セッション削除の戻り値で勝者を 1 人に決める）。
     */
    @Test
    @DisplayName("AC-15 同一セッションへの並列成功でもトークンは1組")
    void 同一セッション並列成功でもトークンは1組() throws Exception {
        MfaUser u = createTotpUser();
        String session = loginForMfaSession(u);
        String c1 = u.backupCodes().get(4);
        String c2 = u.backupCodes().get(5);
        valkey.armReadBarrier(MFA_SESSION_KEY_PREFIX, 2);

        Outcome o = runConcurrently(
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, c1, IP, USER_AGENT),
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, c2, IP, USER_AGENT));

        assertThat(o.successes).as("AC-15 成功は 1 件だけ（失敗: %s）", o.failureCodes).isEqualTo(1);
        assertThat(o.failureCodes).as("AC-15 負けた側はセッション無効").containsExactly("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).as("AC-15 発行トークンは 1 組").isEqualTo(1);
    }
}
