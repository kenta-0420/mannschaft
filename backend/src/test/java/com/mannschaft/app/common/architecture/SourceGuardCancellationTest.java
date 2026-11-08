package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** CMP-260916-0056: 正規表現内の打切りとworker終了を別々に実証する。 */
@DisplayName("ソース番人の時間制限と割込み応答")
class SourceGuardCancellationTest {
    private static final Duration PROBE_BUDGET = Duration.ofMillis(100);
    private static final Duration BOUNDED_WAIT = Duration.ofSeconds(2);

    enum Guard {
        RAW_SQL, DATE_TIME;

        <T> T withinTimeout(Duration budget, Supplier<T> scan) {
            return this == RAW_SQL
                    ? RawSqlTimeColumnGuardTest.scanWithinTimeout(budget, scan)
                    : DateTimeAndZoneGuardTest.scanWithinTimeout(budget, scan);
        }

        Matcher matcher(Pattern pattern, CharSequence source) {
            return this == RAW_SQL
                    ? RawSqlTimeColumnGuardTest.scanMatcher(pattern, source)
                    : DateTimeAndZoneGuardTest.scanMatcher(pattern, source);
        }

        Pattern scanPattern() {
            return this == RAW_SQL ? RawSqlTimeColumnGuardTest.SQL_LOCAL_TIME_FUNCTION
                    : DateTimeAndZoneGuardTest.NO_ARG_NOW;
        }

        List<?> collect(Path root) {
            return this == RAW_SQL ? RawSqlTimeColumnGuardTest.collectViolations(root)
                    : DateTimeAndZoneGuardTest.collectViolations(root);
        }

        List<?> collectFile(String source) {
            return this == RAW_SQL ? RawSqlTimeColumnGuardTest.collectViolationsInFile(source, "example.Sample")
                    : DateTimeAndZoneGuardTest.collectViolationsInFile(source, "example.Sample");
        }
    }

    /**
     * 巨大な仮想入力を実Matcherが読む。割込みを自分で処理しないので、wrapper欠落を隠さない。
     * finallyでのみreleaseし、是正前のREDでもworkerを確実に回収する。
     */
    private static final class ReleasableInput implements CharSequence {
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicLong reads = new AtomicLong();

        @Override
        public int length() {
            return Integer.MAX_VALUE;
        }

        @Override
        public char charAt(int index) {
            if (released.get()) {
                throw new FixtureReleasedException();
            }
            reads.incrementAndGet();
            return 'a';
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new UnsupportedOperationException("一致しない検体なので部分列は取得しない");
        }
    }

    private static final class FixtureReleasedException extends RuntimeException {
    }

    @ParameterizedTest(name = "{0}: 実regexの途中でも短い上限で制御が戻る")
    @EnumSource(Guard.class)
    void realRegexReturnsControlAtTimeout(Guard guard) throws InterruptedException {
        ReleasableInput input = new ReleasableInput();
        AtomicReference<Thread> scanThread = new AtomicReference<>();
        var owner = Executors.newSingleThreadExecutor();
        Throwable outcome;
        try {
            var result = owner.submit(() -> guard.withinTimeout(PROBE_BUDGET, () -> {
                scanThread.set(Thread.currentThread());
                return guard.matcher(guard.scanPattern(), input).find();
            }));
            try {
                result.get(BOUNDED_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                outcome = null;
            } catch (ExecutionException failure) {
                outcome = failure.getCause();
            } catch (TimeoutException failure) {
                // JUnit境界が戻らないREDも、外側の有限待ちで検出する。
                outcome = failure;
            }
        } finally {
            input.released.set(true);
            owner.shutdownNow();
            assertThat(owner.awaitTermination(BOUNDED_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertWorkerGone(scanThread.get());
        }
        assertThat(input.reads.get()).as("実Matcherが検体を読んだこと").isPositive();
        assertThat(outcome).as("外側の救済timeoutではなく番人の上限で戻ること")
                .isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest(name = "{0}: 割込み後に実regex workerが終了し部分結果を返さない")
    @EnumSource(Guard.class)
    void interruptedRegexWorkerActuallyStops(Guard guard) throws InterruptedException {
        ReleasableInput input = new ReleasableInput();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean returnedNormally = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                guard.matcher(guard.scanPattern(), input).find();
                returnedNormally.set(true);
            } catch (Throwable outcome) {
                failure.set(outcome);
            }
        }, "source-guard-regex-probe");
        boolean stoppedBeforeRelease;
        Throwable observedFailure;
        try {
            worker.start();
            await().atMost(BOUNDED_WAIT).until(() -> input.reads.get() > 0);
            worker.interrupt();
            worker.join(BOUNDED_WAIT.toMillis());
            stoppedBeforeRelease = !worker.isAlive();
            observedFailure = failure.get();
        } finally {
            input.released.set(true);
            worker.interrupt();
            assertWorkerGone(worker);
        }
        assertThat(stoppedBeforeRelease).as("finallyの検体release前にworkerが終了すること").isTrue();
        assertThat(observedFailure).isInstanceOf(FlywayMigrationTimeFunctionGuardTest.ScanInterruptedException.class);
        assertThat(returnedNormally.get()).as("キャンセルを正常な部分結果へ変えないこと").isFalse();
    }

    record MatcherPath(Guard guard, String name, Pattern pattern) {
        @Override
        public String toString() {
            return guard + ":" + name;
        }
    }

    static Stream<MatcherPath> matcherPaths() {
        return Stream.of(
                new MatcherPath(Guard.RAW_SQL, "SQL関数", RawSqlTimeColumnGuardTest.SQL_LOCAL_TIME_FUNCTION),
                new MatcherPath(Guard.RAW_SQL, "列への束縛", RawSqlTimeColumnGuardTest.TIME_COLUMN_JAVA_BOUND),
                new MatcherPath(Guard.RAW_SQL, "時刻getter", RawSqlTimeColumnGuardTest.ENTITY_TIME_GETTER),
                new MatcherPath(Guard.RAW_SQL, "生SQL識別", RawSqlTimeColumnGuardTest.RAW_SQL_MARKER),
                new MatcherPath(Guard.DATE_TIME, "now", DateTimeAndZoneGuardTest.NO_ARG_NOW),
                new MatcherPath(Guard.DATE_TIME, "systemDefault", DateTimeAndZoneGuardTest.ZONE_SYSTEM_DEFAULT),
                new MatcherPath(Guard.DATE_TIME, "zoneリテラル", DateTimeAndZoneGuardTest.ZONE_LITERAL),
                new MatcherPath(Guard.DATE_TIME, "フィールドとrecord部分列", DateTimeAndZoneGuardTest.LOCAL_DATE_TIME_DECL),
                new MatcherPath(Guard.DATE_TIME, "recordヘッダ", DateTimeAndZoneGuardTest.RECORD_HEADER),
                new MatcherPath(Guard.DATE_TIME, "型本体", DateTimeAndZoneGuardTest.TYPE_BODY_HEAD),
                new MatcherPath(Guard.DATE_TIME, "呼出ラベル", DateTimeAndZoneGuardTest.TRAILING_CALL_NAME));
    }

    @ParameterizedTest(name = "{0}: matcherが割込みフラグを保持して中断する")
    @MethodSource("matcherPaths")
    void eachMatcherRejectsInterruptedScan(MatcherPath path) {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> path.guard.matcher(path.pattern, "not a matching declaration").find())
                    .isInstanceOf(FlywayMigrationTimeFunctionGuardTest.ScanInterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // JUnit実行スレッドのフラグだけを後始末する。本体wrapperでは消さない。
            Thread.interrupted();
        }
    }

    @ParameterizedTest(name = "{0}: ファイル境界で割込みを正常な空結果へ変えない")
    @EnumSource(Guard.class)
    void interruptedEmptyDirectoryDoesNotReturnPartialSuccess(Guard guard, @TempDir Path root) {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> guard.collect(root))
                    .isInstanceOf(FlywayMigrationTimeFunctionGuardTest.ScanInterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest(name = "{0}: 正常な実走査結果は維持する")
    @EnumSource(Guard.class)
    void normalScanRetainsDetection(Guard guard) {
        String source = guard == Guard.RAW_SQL ? "class Sample { String sql = \"SELECT NOW()\"; }"
                : "class Sample { void run() { LocalDateTime.now(); } }";
        assertThat(guard.withinTimeout(BOUNDED_WAIT, () -> guard.collectFile(source))).hasSize(1);
    }

    @ParameterizedTest(name = "{0}: 読取失敗は伝播する")
    @EnumSource(Guard.class)
    void ioFailureIsNotReturnedAsEmptyResult(Guard guard, @TempDir Path root) {
        Path missing = root.resolve("missing");
        assertThat(Files.notExists(missing)).isTrue();
        assertThatThrownBy(() -> guard.collect(missing)).isInstanceOf(UncheckedIOException.class);
    }

    private static void assertWorkerGone(Thread worker) throws InterruptedException {
        assertThat(worker).as("実走査workerを捕捉できたこと").isNotNull();
        worker.join(BOUNDED_WAIT.toMillis());
        await().atMost(BOUNDED_WAIT).until(() -> !worker.isAlive());
        assertThat(worker.isAlive()).isFalse();
    }
}
