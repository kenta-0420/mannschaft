package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.common.BusinessException;
import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

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

    private static final long UNKNOWN_COUNTER = -1L;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean THREAD_CPU_SUPPORTED = THREADS.isCurrentThreadCpuTimeSupported();
    private static final com.sun.management.OperatingSystemMXBean PROCESS =
            ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
                    ? bean : null;
    private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();

    static {
        // MXBean の初回取得・能力確認・カウンタ読取りは、全ケースの既存 warmUp より前に済ませる。
        // CPU time の有効化など、VM の設定は変更しない。
        snapshotResources();
    }

    // 新規診断APIはfixture・warmUp・既存計測の前に初期化する。設定変更は行わない。
    private static final CompilationMXBean COMPILATION = compilationBean();
    private static final List<MemoryPoolMXBean> MEMORY_POOLS = memoryPools();

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
        String caseKind = limitMillis == ARGON2_LIMIT_MILLIS ? "argon2id" : "bcrypt12";
        logJit(caseKind, "all-mismatch", "before-warmup");
        int warmUpOutcome = warmUp(u);
        logJit(caseKind, "all-mismatch", "after-warmup");
        logWarmUp(caseKind, "all-mismatch", warmUpOutcome);
        String session = loginForMfaSession(u);
        String wrong = wrongBackupCode(u);

        // wall記録と出力は既存nanoTime計測区間の外に置く。
        logJit(caseKind, "all-mismatch", "before-timing");
        ResourceSnapshot resourcesBefore = snapshotResources();
        long startEpochMillis = System.currentTimeMillis();
        long start = System.nanoTime();
        assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                session, MfaVerificationMethod.BACKUP_CODE, wrong, IP, USER_AGENT))
                .as("AC-54 前提: 全不一致は不正コード")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("AUTH_074"));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        long endEpochMillis = System.currentTimeMillis();
        ResourceSnapshot resourcesAfter = snapshotResources();
        logJit(caseKind, "all-mismatch", "after-timing");
        System.out.println("[mfa-latency] pathKind=all-mismatch startEpochMillis=%d endEpochMillis=%d elapsedMillis=%d limitMillis=%d pid=%d".formatted(
                startEpochMillis, endEpochMillis, elapsed, limitMillis, ProcessHandle.current().pid()));
        logResources("all-mismatch", startEpochMillis, endEpochMillis, resourcesBefore, resourcesAfter);
        printResources("all-mismatch", startEpochMillis, resourcesBefore, resourcesAfter);

        assertThat(elapsed).as("AC-54 全不一致の単発所要時間（ms）").isLessThanOrEqualTo(limitMillis);
    }

    private void assertLastMatchWithin(MfaUser u, long limitMillis) throws Exception {
        String caseKind = limitMillis == ARGON2_LIMIT_MILLIS ? "argon2id" : "bcrypt12";
        logJit(caseKind, "last-match", "before-warmup");
        int warmUpOutcome = warmUp(u);
        logJit(caseKind, "last-match", "after-warmup");
        logWarmUp(caseKind, "last-match", warmUpOutcome);
        String session = loginForMfaSession(u);
        String last = u.backupCodes().get(u.backupCodes().size() - 1);

        // wall記録と出力は既存nanoTime計測区間の外に置く。
        logJit(caseKind, "last-match", "before-timing");
        ResourceSnapshot resourcesBefore = snapshotResources();
        long startEpochMillis = System.currentTimeMillis();
        long start = System.nanoTime();
        var response = auth2faService.validateMfaLogin(
                session, MfaVerificationMethod.BACKUP_CODE, last, IP, USER_AGENT);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        long endEpochMillis = System.currentTimeMillis();
        ResourceSnapshot resourcesAfter = snapshotResources();
        logJit(caseKind, "last-match", "after-timing");
        System.out.println("[mfa-latency] pathKind=last-match startEpochMillis=%d endEpochMillis=%d elapsedMillis=%d limitMillis=%d pid=%d".formatted(
                startEpochMillis, endEpochMillis, elapsed, limitMillis, ProcessHandle.current().pid()));
        logResources("last-match", startEpochMillis, endEpochMillis, resourcesBefore, resourcesAfter);
        printResources("last-match", startEpochMillis, resourcesBefore, resourcesAfter);

        assertThat(response.getData().getAccessToken()).as("AC-54 前提: 最後の 1 件で一致して成功").isNotBlank();
        assertThat(elapsed).as("AC-54 最後一致の単発所要時間（ms）").isLessThanOrEqualTo(limitMillis);
    }

    /**
     * 既存の nanoTime 計測区間の外側で資源カウンタを採取する。
     * カウンタは同時採取ではなく、前後の snapshot 全体の窓は既存の wall 計測より広い。
     * thread CPU は呼出しスレッドのみ、process CPU は当該 fork の全スレッドのみを表す。
     * GC 時間は各 collector の累積値であり、STW 時間の全量や CPU 時間とは同一視しない。
     */
    private static ResourceSnapshot snapshotResources() {
        long startNanos = System.nanoTime();
        long startEpochMillis = System.currentTimeMillis();
        long threadCpuNanos = readCounter(() -> THREAD_CPU_SUPPORTED && THREADS.isThreadCpuTimeEnabled()
                ? THREADS.getCurrentThreadCpuTime() : UNKNOWN_COUNTER);
        long processCpuNanos = readCounter(() -> PROCESS == null ? UNKNOWN_COUNTER : PROCESS.getProcessCpuTime());
        long gcCount = totalGcCounter(GarbageCollectorMXBean::getCollectionCount);
        long gcMillis = totalGcCounter(GarbageCollectorMXBean::getCollectionTime);
        long endEpochMillis = System.currentTimeMillis();
        long endNanos = System.nanoTime();
        return new ResourceSnapshot(startNanos, endNanos, startEpochMillis, endEpochMillis,
                threadCpuNanos, processCpuNanos, gcCount, gcMillis);
    }

    /** 未対応・無効・取得拒否を 0 へ変換せず、unknown として保持する。 */
    private static long readCounter(LongSupplier counter) {
        try {
            long value = counter.getAsLong();
            return value < 0 ? UNKNOWN_COUNTER : value;
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            return UNKNOWN_COUNTER;
        }
    }

    /** collector の一部でも未対応なら、合計を既知の 0 として扱わない。 */
    private static long totalGcCounter(ToLongFunction<GarbageCollectorMXBean> counter) {
        if (COLLECTORS.isEmpty()) {
            return UNKNOWN_COUNTER;
        }
        long total = 0;
        for (GarbageCollectorMXBean collector : COLLECTORS) {
            long value = readCounter(() -> counter.applyAsLong(collector));
            if (value < 0 || Long.MAX_VALUE - total < value) {
                return UNKNOWN_COUNTER;
            }
            total += value;
        }
        return total;
    }

    /** 負値・カウンタ減少があれば、差分も unknown とする。 */
    private static long counterDelta(long before, long after) {
        return before < 0 || after < before ? UNKNOWN_COUNTER : after - before;
    }

    private static void printResources(String pathKind, long startEpochMillis,
                                       ResourceSnapshot before, ResourceSnapshot after) {
        long threadCpuNanos = counterDelta(before.threadCpuNanos(), after.threadCpuNanos());
        long processCpuNanos = counterDelta(before.processCpuNanos(), after.processCpuNanos());
        long gcCount = counterDelta(before.gcCount(), after.gcCount());
        long gcMillis = counterDelta(before.gcMillis(), after.gcMillis());
        // 旧 [mfa-latency] 行の完全一致抽出を維持するため、診断は別の数値限定行に出す。
        System.out.println(("[mfa-resource] pathKind=%s startEpochMillis=%d pid=%d"
                + " preStartNanos=%d preEndNanos=%d preStartEpochMillis=%d preEndEpochMillis=%d preSnapshotNanos=%d"
                + " postStartNanos=%d postEndNanos=%d postStartEpochMillis=%d postEndEpochMillis=%d postSnapshotNanos=%d"
                + " snapshotWindowNanos=%d threadCpuNanos=%d threadCpuKnown=%d processCpuNanos=%d processCpuKnown=%d"
                + " gcCount=%d gcCountKnown=%d gcMillis=%d gcMillisKnown=%d").formatted(
                pathKind, startEpochMillis, ProcessHandle.current().pid(),
                before.startNanos(), before.endNanos(), before.startEpochMillis(), before.endEpochMillis(),
                before.endNanos() - before.startNanos(),
                after.startNanos(), after.endNanos(), after.startEpochMillis(), after.endEpochMillis(),
                after.endNanos() - after.startNanos(), after.endNanos() - before.startNanos(),
                threadCpuNanos, threadCpuNanos >= 0 ? 1 : 0, processCpuNanos, processCpuNanos >= 0 ? 1 : 0,
                gcCount, gcCount >= 0 ? 1 : 0, gcMillis, gcMillis >= 0 ? 1 : 0));
    }

    /** 1 回の採取区間の両端時刻と、その区間内で順に読んだ累積カウンタ。 */
    private record ResourceSnapshot(long startNanos, long endNanos, long startEpochMillis, long endEpochMillis,
                                    long threadCpuNanos, long processCpuNanos, long gcCount, long gcMillis) {
    }

    /** 固定allowlistのgetterだけを読む。未対応・未取得は-1、booleanは0/1。 */
    @BeforeAll
    static void recordJvmProfile() {
        HotSpotDiagnosticMXBean hotspot;
        try {
            hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        } catch (RuntimeException | LinkageError ignored) {
            hotspot = null;
        }
        long tiered = vmOption(hotspot, "TieredCompilation");
        long stopAt = vmOption(hotspot, "TieredStopAtLevel");
        long compilerCount = vmOption(hotspot, "CICompilerCount");
        long threshold = vmOption(hotspot, "CompileThreshold");
        long cacheSize = vmOption(hotspot, "ReservedCodeCacheSize");
        var version = Runtime.version();
        System.out.println("[mfa-jit] caseKind=class pathKind=none phase=vm-profile pid=%d epochMillis=%d jdkFeature=%d jdkInterim=%d jdkUpdate=%d jdkPatch=%d jdkBuild=%d vendorKind=%d jvmStartTimeMillis=%d TieredCompilation=%d TieredStopAtLevel=%d CICompilerCount=%d CompileThreshold=%d ReservedCodeCacheSize=%d".formatted(
                ProcessHandle.current().pid(), System.currentTimeMillis(), version.feature(), version.interim(), version.update(), version.patch(),
                version.build().orElse(-1), vendorKind(),
                jvmStartTimeMillis(), tiered, stopAt, compilerCount, threshold, cacheSize));
    }

    private static long vmOption(HotSpotDiagnosticMXBean hotspot, String name) {
        if (hotspot == null) {
            return -1;
        }
        try {
            String value = hotspot.getVMOption(name).getValue();
            return "true".equals(value) ? 1 : "false".equals(value) ? 0 : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** vendor固定分類: 0=その他、1=Adoptium、2=Oracle、3=Amazon、4=Azul、-1=未取得。 */
    private static int vendorKind() {
        try {
            return switch (System.getProperty("java.vendor", "")) {
                case "" -> -1;
                case "Eclipse Adoptium" -> 1;
                case "Oracle Corporation" -> 2;
                case "Amazon.com Inc." -> 3;
                case "Azul Systems, Inc." -> 4;
                default -> 0;
            };
        } catch (SecurityException ignored) {
            return -1;
        }
    }

    private static long jvmStartTimeMillis() {
        try {
            return ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static CompilationMXBean compilationBean() {
        try {
            return ManagementFactory.getCompilationMXBean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<MemoryPoolMXBean> memoryPools() {
        try {
            return ManagementFactory.getMemoryPoolMXBeans();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static long compilationMillis() {
        try {
            return COMPILATION != null && COMPILATION.isCompilationTimeMonitoringSupported()
                    ? COMPILATION.getTotalCompilationTime() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static MemoryUsage codeHeap(String name) {
        try {
            for (var pool : MEMORY_POOLS) {
                if (name.equals(pool.getName())) {
                    return pool.getUsage();
                }
            }
        } catch (RuntimeException ignored) {
            // 未取得はnullを経由してused/maxとも-1とする。例外本文は出力しない。
        }
        return null;
    }

    /** 累積値を既存計測窓の外で記録する。CodeHeap以外のpool名は出力しない。 */
    private static void logJit(String caseKind, String pathKind, String phase) {
        var nonMethods = codeHeap("CodeHeap 'non-nmethods'");
        var profiled = codeHeap("CodeHeap 'profiled nmethods'");
        var nonProfiled = codeHeap("CodeHeap 'non-profiled nmethods'");
        System.out.println("[mfa-jit] caseKind=%s pathKind=%s phase=%s pid=%d epochMillis=%d compilationMillis=%d nonMethodsUsed=%d nonMethodsMax=%d profiledUsed=%d profiledMax=%d nonProfiledUsed=%d nonProfiledMax=%d".formatted(
                caseKind, pathKind, phase, ProcessHandle.current().pid(), System.currentTimeMillis(), compilationMillis(),
                nonMethods == null ? -1 : nonMethods.getUsed(), nonMethods == null ? -1 : nonMethods.getMax(),
                profiled == null ? -1 : profiled.getUsed(), profiled == null ? -1 : profiled.getMax(),
                nonProfiled == null ? -1 : nonProfiled.getUsed(), nonProfiled == null ? -1 : nonProfiled.getMax()));
    }

    /** outcome: 正常0/AUTH_074=1/その他Business=2/UnsupportedOperationException=3。 */
    private static void logWarmUp(String caseKind, String pathKind, int outcome) {
        System.out.println("[mfa-warmup] caseKind=%s pathKind=%s pid=%d epochMillis=%d outcome=%d".formatted(
                caseKind, pathKind, ProcessHandle.current().pid(), System.currentTimeMillis(), outcome));
    }

    /** wall窓・PIDで既存latency行と対応付ける。CPU/GCの採取窓はwall窓を外側から含む。 */
    private static void logResources(String pathKind, long startEpochMillis, long endEpochMillis,
                                     ResourceSnapshot before, ResourceSnapshot after) {
        System.out.println("[mfa-resources] pathKind=%s startEpochMillis=%d endEpochMillis=%d pid=%d threadCpuNanos=%d gcCount=%d gcTimeMillis=%d".formatted(
                pathKind, startEpochMillis, endEpochMillis, ProcessHandle.current().pid(),
                counterDelta(before.threadCpuNanos(), after.threadCpuNanos()),
                counterDelta(before.gcCount(), after.gcCount()),
                counterDelta(before.gcMillis(), after.gcMillis())));
    }

    /** JIT・コネクション確立の影響を除くための空打ち（別セッションで 1 回失敗させる）。 */
    private int warmUp(MfaUser u) throws Exception {
        String session = loginForMfaSession(u);
        int outcome = 0;
        try {
            auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, wrongBackupCode(u), IP, USER_AGENT);
        } catch (BusinessException | UnsupportedOperationException ignored) {
            // 空打ちの結果は問わない（計測対象ではない）。診断enumだけを返す。
            if (ignored instanceof BusinessException business) {
                outcome = business.getErrorCode() != null && "AUTH_074".equals(business.getErrorCode().getCode()) ? 1 : 2;
            } else {
                outcome = 3;
            }
        }
        return outcome;
    }
}
