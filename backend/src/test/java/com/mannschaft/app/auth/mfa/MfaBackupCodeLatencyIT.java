package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261012-0910 試練: バックアップコード照合の単発所要時間の上限（AC-54）。
 *
 * <h2>上限値と根拠</h2>
 * <p>バックアップコードは 1 件ずつパスワードハッシュで保存されており、照合は最大 8 回のハッシュ比較になる
 * （軍議「攻め口5類型・性能」: 試行上限で総量は抑えられるため単発の遅さは許容、p95 1.5 秒目安）。</p>
 * <ul>
 *   <li><b>Argon2id（現行の新規ハッシュ。{@code {argon2}} 接頭辞・Spring Security v5.8 既定 m=16MiB,t=2）</b>:
 *       1 照合おおむね 30〜100ms × 8 回 ≒ 0.8 秒以下。軍議の目安 {@value #ARGON2_LIMIT_MILLIS}ms を上限とする。</li>
 *   <li><b>BCrypt cost 12（Argon2id 移行前に発行された旧ハッシュ。接頭辞なし）</b>:
 *       1 照合おおむね 200〜300ms × 8 回 ≒ 2.4 秒以下。{@value #BCRYPT_LIMIT_MILLIS}ms を上限とする
 *       （本番に実データが無いため旧ハッシュのバックアップコードは実在しないが、DelegatingPasswordEncoder が
 *       接頭辞なしを BCrypt として照合する以上、経路としては存在する）。</li>
 * </ul>
 * <p>上限超過は「8 件を超える重複照合（全件 2 周・照合後の再読込での再照合等）」や「ロック待ち・N+1」の混入を示す。
 * 計測は Service 単発呼び出し（MockMvc を含めない）で、JIT・接続確立の影響を除くため事前に 1 回空打ちする。</p>
 * <p>最悪経路は「全不一致」（8 件すべて照合）と「最後の 1 件で一致」（8 件目で一致）の 2 つ。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 バックアップコード照合の単発所要時間（AC-54）")
class MfaBackupCodeLatencyIT extends MfaItSupport {

    /** Argon2id ハッシュ 8 件の単発上限（ミリ秒）。 */
    static final long ARGON2_LIMIT_MILLIS = 1_500L;
    /** BCrypt cost 12 ハッシュ 8 件の単発上限（ミリ秒）。 */
    static final long BCRYPT_LIMIT_MILLIS = 3_000L;

    private static final String IP = "203.0.113.54";

    private final Function<String, String> argon2 = code ->
            "{argon2}" + Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(code);
    private final Function<String, String> bcryptLegacy = code -> new BCryptPasswordEncoder(12).encode(code);

    /** AC-54: Argon2id 8 件・全不一致の単発所要時間 ≤ 1.5 秒。 */
    @Test
    @DisplayName("AC-54 Argon2id 8件 全不一致は1.5秒以内")
    void Argon2id全不一致の所要時間() throws Exception {
        assertMismatchWithin(createMfaUser(TwoFactorMethod.TOTP, 8, argon2), ARGON2_LIMIT_MILLIS);
    }

    /** AC-54: Argon2id 8 件・最後の 1 件で一致の単発所要時間 ≤ 1.5 秒。 */
    @Test
    @DisplayName("AC-54 Argon2id 8件 最後一致は1.5秒以内")
    void Argon2id最後一致の所要時間() throws Exception {
        assertLastMatchWithin(createMfaUser(TwoFactorMethod.TOTP, 8, argon2), ARGON2_LIMIT_MILLIS);
    }

    /** AC-54: 旧 BCrypt(cost 12) 8 件・全不一致の単発所要時間 ≤ 3.0 秒。 */
    @Test
    @DisplayName("AC-54 旧BCrypt 8件 全不一致は3秒以内")
    void BCrypt全不一致の所要時間() throws Exception {
        assertMismatchWithin(createMfaUser(TwoFactorMethod.TOTP, 8, bcryptLegacy), BCRYPT_LIMIT_MILLIS);
    }

    /** AC-54: 旧 BCrypt(cost 12) 8 件・最後の 1 件で一致の単発所要時間 ≤ 3.0 秒。 */
    @Test
    @DisplayName("AC-54 旧BCrypt 8件 最後一致は3秒以内")
    void BCrypt最後一致の所要時間() throws Exception {
        assertLastMatchWithin(createMfaUser(TwoFactorMethod.TOTP, 8, bcryptLegacy), BCRYPT_LIMIT_MILLIS);
    }

    // ─────────────────────────────────────────────────────────────

    private void assertMismatchWithin(MfaUser u, long limitMillis) throws Exception {
        warmUp(u);
        String session = loginForMfaSession(u);
        String wrong = wrongBackupCode(u);

        // wall記録と出力は既存nanoTime計測区間の外に置く。
        long startEpochMillis = System.currentTimeMillis();
        long start = System.nanoTime();
        assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                session, MfaVerificationMethod.BACKUP_CODE, wrong, IP, USER_AGENT))
                .as("AC-54 前提: 全不一致は不正コード")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("AUTH_074"));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        long endEpochMillis = System.currentTimeMillis();
        System.out.println("[mfa-latency] pathKind=all-mismatch startEpochMillis=%d endEpochMillis=%d elapsedMillis=%d limitMillis=%d pid=%d".formatted(
                startEpochMillis, endEpochMillis, elapsed, limitMillis, ProcessHandle.current().pid()));

        assertThat(elapsed).as("AC-54 全不一致の単発所要時間（ms）").isLessThanOrEqualTo(limitMillis);
    }

    private void assertLastMatchWithin(MfaUser u, long limitMillis) throws Exception {
        warmUp(u);
        String session = loginForMfaSession(u);
        String last = u.backupCodes().get(u.backupCodes().size() - 1);

        // wall記録と出力は既存nanoTime計測区間の外に置く。
        long startEpochMillis = System.currentTimeMillis();
        long start = System.nanoTime();
        var response = auth2faService.validateMfaLogin(
                session, MfaVerificationMethod.BACKUP_CODE, last, IP, USER_AGENT);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        long endEpochMillis = System.currentTimeMillis();
        System.out.println("[mfa-latency] pathKind=last-match startEpochMillis=%d endEpochMillis=%d elapsedMillis=%d limitMillis=%d pid=%d".formatted(
                startEpochMillis, endEpochMillis, elapsed, limitMillis, ProcessHandle.current().pid()));

        assertThat(response.getData().getAccessToken()).as("AC-54 前提: 最後の 1 件で一致して成功").isNotBlank();
        assertThat(elapsed).as("AC-54 最後一致の単発所要時間（ms）").isLessThanOrEqualTo(limitMillis);
    }

    /** JIT・コネクション確立の影響を除くための空打ち（別セッションで 1 回失敗させる）。 */
    private void warmUp(MfaUser u) throws Exception {
        String session = loginForMfaSession(u);
        try {
            auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, wrongBackupCode(u), IP, USER_AGENT);
        } catch (BusinessException | UnsupportedOperationException ignored) {
            // 空打ちの結果は問わない（計測対象ではない）
        }
    }
}
