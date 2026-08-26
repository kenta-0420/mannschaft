package com.mannschaft.app.common.architecture;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.batch.BatchEndpointExempt;
import com.mannschaft.app.common.batch.PodLocalScheduled;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * バッチ規約（{@code @Scheduled} を書くときの必須併記）を機械的に強制する番人（issue #2601 Phase 1-a）。
 *
 * <h2>背景 — 「人間の善意」に依存していた規約</h2>
 * <p>本番は複数 Pod で動く。{@code @Scheduled} が付いたメソッドは、
 * <b>何もしなければ Pod 数だけ同時に走る</b>。通知送出バッチなら同じ通知が Pod 数だけ届き、
 * 課金・集計バッチなら金額や集計値が Pod 数だけ多重計上される。
 * これを防ぐのが ShedLock（{@code @SchedulerLock}）による分散排他だが、
 * 従来この併記は<b>規約ドキュメントと {@code ShedLockConfig} の Javadoc 一覧という
 * 「人間の善意」だけ</b>で維持されていた。付け忘れても CI は緑のままであり、
 * 本番で二重実行が起きるまで誰も気づけない。</p>
 *
 * <p>本番人は、その規約を CI で機械的に強制する。</p>
 *
 * <h2>本番人が固定する 5 つの不変条件</h2>
 * <ol>
 *   <li><b>{@code @Scheduled} ⇒ {@code @SchedulerLock} 必須</b>
 *       （例外は {@link PodLocalScheduled} を付けた監査済みのもののみ）。
 *       多重実行＝二重通知・二重課金を構造的に防ぐ。</li>
 *   <li><b>{@code @SchedulerLock} ⇒ {@code lockAtMostFor} の明示必須</b>。
 *       未指定だと {@code @EnableSchedulerLock(defaultLockAtMostFor = "30m")} の
 *       <b>既定値に暗黙依存</b>する。既定 30 分は「数秒で終わるワーカー」には長すぎ
 *       （Pod が異常終了するとロックが 30 分残り、その間バッチが完全停止する）、
 *       「1 時間かかる夜間集計」には短すぎる（処理中に他 Pod が二重起動しうる）。
 *       どちらも既定値を眺めているだけでは気づけないため、
 *       <b>そのバッチの最大実行時間を書き手に必ず考えさせる</b>のが本ルールの目的である。</li>
 *   <li><b>{@code @Scheduled} ⇒ {@code @BatchEndpoint} 必須</b>
 *       （例外は {@link BatchEndpointExempt} を付けた監査済みのもののみ）。
 *       {@link BatchEndpoint} が無いバッチは名前で起動できず実行履歴も残らないため、
 *       実機検証も障害調査もできない。</li>
 *   <li><b>短周期バッチは {@code lockAtMostFor} > 起動間隔</b>（issue #2601 Phase 2）。
 *       {@code lockAtMostFor} が起動間隔以下だと、1 回の実行がその時間を超えた瞬間に
 *       ロックが失効し、次の起動が前の実行と重なる。<b>同値が最も危険</b>で、
 *       実行がわずかに超過しただけで重なる。ルール 2 が「値を書かせる」ところまでしか
 *       強制できないのに対し、本ルールは<b>書かれた値が実際に足りているか</b>を検証する。
 *       設定だけで起きる事故であり、コードを読んでも気づけないため機械的に弾く。
 *       適用範囲を高頻度（起動間隔 1 時間以下）に限る理由は
 *       {@link #HIGH_FREQUENCY_THRESHOLD} の Javadoc を参照。</li>
 *   <li><b>{@code @SchedulerLock} 付きメソッドはプリミティブ型を返してはならない</b>
 *       （issue #2724）。ShedLock は {@code LockingNotSupportedException:
 *       Can not lock method returning primitive value} を投げ、当該メソッドは
 *       {@code @Scheduled} 実行のたびに<b>必ず失敗し一度も走らない</b>（
 *       {@code ReservationPendingExpireBatchService} が {@code int} を返して
 *       この事故を起こしていた）。原因はコンパイルもテスト（モック経由の単体テスト）も
 *       すり抜け、実機の {@code @Scheduled} 実行でしか露見しないため機械的に弾く。
 *       戻り値が不要なら {@code void}、可観測性が必要なら参照型（{@code Integer} 等）
 *       か戻り値を使わず {@code log.info} で件数を出す設計に直すこと。</li>
 * </ol>
 *
 * <h2>発足時点の状態（試練＝テスト先行のため red で始まる）</h2>
 * <p>ルール 1 とルール 3 は<b>発足時点で違反が残っている</b>。これは意図した状態である。
 * 受け入れ条件から先に失敗するテストを置き（試練）、実装＝既存バッチの是正（Phase 1-c / Phase 3）で
 * green 化する、というプロジェクトの開発フローに従っている。
 * ルール 2 は発足時点で違反 0 件（green 発足）であり、目的は<b>新規流入の防止</b>である。
 * ルール 4（Phase 2）も同じく試練＝ red 先行で発足し、既存 21 件の是正で green 化した
 * （除外リストは設けていない）。
 * ルール 5（issue #2724）も同じく試練先行で発足し、{@code ReservationPendingExpireBatchService}
 * 1 件の是正（プリミティブ {@code int} から参照型 {@code Integer} への変更。ShedLock は
 * プリミティブ戻り値だけをロックできず、参照型なら問題ないため）で green 化した。</p>
 *
 * <h2>green 発足するルールの「偽陰性ゼロ」証明</h2>
 * <p>「違反 0 件」は<b>番人が動いていることの証明にはならない</b> ——
 * 判定ロジックが常に空リストを返す壊れ方をしていても、本番走査は緑のままだからである。
 * よって本番人の判定ロジック（{@link #findMissingSchedulerLock}、
 * {@link #findMissingLockAtMostFor}、{@link #findMissingBatchEndpoint}、
 * {@link #findInsufficientLockAtMostFor}）は
 * すべて package-visible な static ヘルパへ切り出してあり、
 * メタテスト {@link ScheduledBatchGuardConditionTest} が
 * {@code architecture/fixtures/} の意図的違反 fixture に対して<b>同じヘルパ</b>を評価する
 * （判定ロジックの二重実装を避け、メタテストが実際の判定を検証していることを保証する）。</p>
 *
 * <h2>{@code @Repeatable} の取りこぼし対策</h2>
 * <p>{@link Scheduled} は {@code @Repeatable(Schedules.class)} である。
 * 1 メソッドに 2 つ以上書くと、javac は {@code @Scheduled} を<b>直接は付けず</b>
 * {@code @Schedules({...})} コンテナに包んで出力する。
 * したがって {@code areAnnotatedWith(Scheduled.class)} だけでは<b>複数スケジュール指定の
 * バッチが丸ごと番人の対象外になる</b>（＝最も見逃したくない「複雑なバッチ」ほど漏れる）。
 * 本番人は {@link #isScheduled(JavaMethod)} で {@link Schedules} コンテナも対象に含め、
 * メタテストがこのケースを fixture で実証する。</p>
 *
 * <h2>凍結ストア（{@code FreezingArchRule}）を使わない理由</h2>
 * <p>兄弟番人（{@link CacheableAuthzEnforcementGuardTest} 等）と同じ判断である。
 * {@code ./gradlew test --tests "..."} の絞り込み実行で凍結ストアを破壊する事故
 * （{@link ArchUnitFreezeStoreIntegrityTest} が検知している事故）を新たに持ち込まないため、
 * および本番デプロイ前の今こそ例外を最小化すべきであるため、凍結ストアは使わない。
 * 例外は {@link PodLocalScheduled} / {@link BatchEndpointExempt} という
 * <b>理由の記述を必須とするマーカー</b>だけで表現し、その付与要件自体を
 * 二次番人 {@code BatchMarkerAnnotationGuardTest} が検証する。</p>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ScheduledBatchGuardTest {

    // ------------------------------------------------------------------
    // ルール 1: @Scheduled ⇒ @SchedulerLock 必須
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule scheduled_methods_should_declare_scheduler_lock =
        methods().that(areScheduled())
            .should(satisfy("declare @SchedulerLock (or be audited as @PodLocalScheduled)",
                ScheduledBatchGuardTest::findMissingSchedulerLock))
            .because("本番は複数 Pod で動くため、分散排他の無い @Scheduled は Pod 数だけ同時に走る。"
                + "通知バッチなら同じ通知が Pod 数だけ届き、課金・集計バッチなら多重計上になる。"
                + "Pod ごとに走ることが設計意図である場合のみ @PodLocalScheduled で理由とともに明示すること（issue #2601）")
            .as("@Scheduled methods should declare @SchedulerLock for multi-pod exclusion");

    // ------------------------------------------------------------------
    // ルール 2: @SchedulerLock ⇒ lockAtMostFor 明示必須
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule scheduler_lock_should_declare_lock_at_most_for =
        methods().that().areAnnotatedWith(SchedulerLock.class)
            .should(satisfy("declare an explicit lockAtMostFor",
                ScheduledBatchGuardTest::findMissingLockAtMostFor))
            .because("lockAtMostFor 未指定は @EnableSchedulerLock の既定値 30m への暗黙依存になる。"
                + "既定 30m は数秒で終わるワーカーには長すぎ（Pod 異常終了時にバッチが 30 分停止する）、"
                + "1 時間かかる夜間集計には短すぎる（処理中に他 Pod が二重起動しうる）。"
                + "そのバッチの最大実行時間を書き手に必ず考えさせるため明示を必須とする（issue #2601）")
            .as("@SchedulerLock should declare an explicit lockAtMostFor");

    // ------------------------------------------------------------------
    // ルール 3: @Scheduled ⇒ @BatchEndpoint 必須
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule scheduled_methods_should_declare_batch_endpoint =
        methods().that(areScheduled())
            .should(satisfy("declare @BatchEndpoint (or be audited as @BatchEndpointExempt)",
                ScheduledBatchGuardTest::findMissingBatchEndpoint))
            .because("@BatchEndpoint の無いバッチは名前で起動できず実行履歴も残らないため、"
                + "実機検証も障害調査もできない。数秒間隔の高頻度ワーカーのように履歴が有害な場合のみ"
                + "@BatchEndpointExempt で理由とともに明示すること（issue #2601）")
            .as("@Scheduled methods should declare @BatchEndpoint for operability");

    // ------------------------------------------------------------------
    // ルール 4: 高頻度バッチの lockAtMostFor は実行間隔を上回ること
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule lock_at_most_for_should_exceed_schedule_interval =
        methods().that().areAnnotatedWith(SchedulerLock.class)
            .should(satisfy("declare a lockAtMostFor longer than the schedule interval",
                ScheduledBatchGuardTest::findInsufficientLockAtMostFor))
            .because("実行間隔以下の lockAtMostFor は、処理が長引いた瞬間にロックが失効し、"
                + "次の周回と重なって同じ処理が並走する。設定だけで起きる事故であり"
                + "コードを読んでも気づけないため機械的に弾く（issue #2601）")
            .as("@SchedulerLock lockAtMostFor should exceed the schedule interval for high-frequency batches");

    // ------------------------------------------------------------------
    // ルール 5: @SchedulerLock 付きメソッドはプリミティブ戻り値禁止
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule scheduler_lock_methods_should_not_return_primitives =
        methods().that().areAnnotatedWith(SchedulerLock.class)
            .should(satisfy("not return a primitive type",
                ScheduledBatchGuardTest::findPrimitiveReturningSchedulerLock))
            .because("ShedLock はプリミティブ戻り値のメソッドをロックできず、"
                + "LockingNotSupportedException: Can not lock method returning primitive value を投げて"
                + "@Scheduled 実行のたびに必ず失敗する（一度も実行されない）。"
                + "コンパイルもモック経由の単体テストもすり抜け、実機の @Scheduled 実行でしか露見しないため"
                + "機械的に弾く（issue #2724）")
            .as("@SchedulerLock methods should not return a primitive type");

    // ══════════════════════════════════════════════════════════════════
    // 判定ロジック（メタテストと共有する単一正準）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 本ルールを適用する「高頻度」の境界（実行間隔がこれ以下のバッチだけを対象にする）。
     *
     * <p><b>なぜ全バッチに一律適用しないのか</b> —— 重なりが起きるのは
     * 「実行がロック期限を超え、<b>かつ</b>その最中に次の起動が来る」ときだけである。
     * 日次・週次・月次バッチは次の起動まで 24 時間以上あるため、
     * {@code lockAtMostFor} が間隔より短くても重なりは起こらない。
     * むしろ日次バッチに 24 時間超のロックを強いると、Pod が異常終了したときに
     * <b>バッチが丸一日停止する</b>（規約 §30.1 が「日次・週次・月次は間隔ではなく
     * 最悪ケースの処理時間から決める」としているのはこのためである）。
     * よって構造的な保証（{@code lockAtMostFor > 実行間隔}）を機械的に要求するのは、
     * 規約が「間隔の 3 倍を基本とする」と定めている<b>短周期バッチ</b>に限る。</p>
     */
    static final Duration HIGH_FREQUENCY_THRESHOLD = Duration.ofHours(1);

    /** cron の最小発火間隔を求めるときの探索上限（発火回数）。 */
    private static final int CRON_PROBE_MAX_FIRINGS = 5_000;

    /** cron の最小発火間隔を求めるときの探索上限（暦の窓。月次・曜日指定を必ず 1 周させる長さ）。 */
    private static final Duration CRON_PROBE_HORIZON = Duration.ofDays(400);

    /** cron 探索の起点（曜日・月末を偏りなく踏むよう固定日時を使い、判定を実行時刻に依存させない）。 */
    private static final LocalDateTime CRON_PROBE_START = LocalDateTime.of(2027, 1, 1, 0, 0);

    /** {@code ${prop}} / {@code ${prop:default}} 形式のプロパティプレースホルダ。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$", Pattern.DOTALL);

    /** ShedLock の ISO-8601 表記判定（{@code StringToDurationConverter} と同一）。 */
    private static final Pattern ISO_8601_DURATION = Pattern.compile("^[+\\-]?P.*$");

    /** ShedLock の簡易表記判定（{@code StringToDurationConverter} と同一）。 */
    private static final Pattern SIMPLE_DURATION = Pattern.compile("^([+\\-]?\\d+)([a-zA-Z]{0,2})$");

    /** ShedLock の簡易表記の単位（{@code StringToDurationConverter} と同一。<b>単位省略はミリ秒</b>）。 */
    private static final Map<String, ChronoUnit> SHEDLOCK_UNITS = Map.of(
        "us", ChronoUnit.MICROS,
        "ns", ChronoUnit.NANOS,
        "ms", ChronoUnit.MILLIS,
        "s", ChronoUnit.SECONDS,
        "m", ChronoUnit.MINUTES,
        "h", ChronoUnit.HOURS,
        "d", ChronoUnit.DAYS,
        "", ChronoUnit.MILLIS);

    /**
     * {@code lockAtMostFor} が実行間隔を上回っていない高頻度バッチを違反として返す。
     *
     * <p>ルール 1（ロック必須）・ルール 2（{@code lockAtMostFor} 明示必須）が担当する形は
     * ここでは鳴らさない（同じ欠陥で 2 つのルールが二重に鳴ると、どれを直せばよいか分からなくなる）。</p>
     *
     * @param method 検査対象メソッド
     * @return 違反の説明文リスト（空なら合格）
     */
    static List<String> findInsufficientLockAtMostFor(JavaMethod method) {
        if (!isScheduled(method) || !method.isAnnotatedWith(SchedulerLock.class)) {
            return List.of();
        }
        SchedulerLock lock = method.getAnnotationOfType(SchedulerLock.class);
        if (lock.lockAtMostFor().isBlank()) {
            return List.of(); // ルール 2 の担当
        }

        String resolvedLock = resolvePlaceholder(lock.lockAtMostFor());
        Duration lockAtMostFor = resolvedLock == null ? null : parseShedLockDuration(resolvedLock);
        if (lockAtMostFor == null) {
            return List.of(String.format(
                "%s の lockAtMostFor = \"%s\" を CI から解釈できません。"
                    + "解釈できない値は実行間隔との比較ができず、番人が何も守れなくなります。"
                    + "ISO-8601（\"PT10M\"）か ShedLock の簡易表記（\"10m\"）で、"
                    + "プレースホルダを使う場合は既定値付き（\"${prop:PT10M}\"）で書いてください。(%s)",
                method.getFullName(), lock.lockAtMostFor(), method.getSourceCodeLocation()));
        }

        List<String> violations = new ArrayList<>();
        for (Scheduled scheduled : scheduleAnnotations(method)) {
            Cadence cadence = cadenceOf(scheduled);
            if (cadence.interval() == null) {
                violations.add(String.format(
                    "%s の起動間隔を CI から確認できません（%s）。"
                        + "間隔が分からなければ lockAtMostFor が足りているかを機械的に検証できず、"
                        + "『cron を外部プロパティへ追い出す』が番人の抜け道になります。"
                        + "既定値付きプレースホルダ（\"${prop:0 0 3 * * *}\"）で"
                        + "本番の起動間隔がコード上から読めるようにしてください。(%s)",
                    method.getFullName(), cadence.undecidableReason(), method.getSourceCodeLocation()));
                continue;
            }
            if (cadence.interval().compareTo(HIGH_FREQUENCY_THRESHOLD) > 0) {
                // 低頻度バッチ（日次・週次・月次）は次の起動まで十分間隔があるため重なりが起きない。
                // これらの lockAtMostFor は最悪ケースの処理時間から決める（規約 §30.1）。
                continue;
            }
            if (lockAtMostFor.compareTo(cadence.interval()) > 0) {
                continue;
            }
            violations.add(String.format(
                "%s は %s（起動間隔 %s）に対して lockAtMostFor = \"%s\"（%s）であり、"
                    + "実行間隔を上回っていません。1 回の実行が lockAtMostFor を超えた時点でロックが失効し、"
                    + "次の起動と重なって同じ処理が並走します（同値が最も危険です）。"
                    + "短周期バッチは起動間隔の 3 倍を基本に lockAtMostFor を設定してください"
                    + "（規約: backend/.claudecode.md §30.1）。(%s)",
                method.getFullName(), cadence.description(), format(cadence.interval()),
                lock.lockAtMostFor(), format(lockAtMostFor), method.getSourceCodeLocation()));
        }
        return violations;
    }

    /**
     * メソッドに付いた {@code @Scheduled} をすべて返す（{@link Schedules} コンテナ込み）。
     *
     * @param method 検査対象メソッド
     * @return スケジュール指定の一覧
     */
    static List<Scheduled> scheduleAnnotations(JavaMethod method) {
        if (method.isAnnotatedWith(Schedules.class)) {
            return List.of(method.getAnnotationOfType(Schedules.class).value());
        }
        if (method.isAnnotatedWith(Scheduled.class)) {
            return List.of(method.getAnnotationOfType(Scheduled.class));
        }
        return List.of();
    }

    /**
     * 1 つの {@code @Scheduled} から起動間隔を求める。
     *
     * <p>{@code cron} / {@code fixedRate} / {@code fixedDelay} の 3 形式すべてに対応する
     * （文字列版 {@code fixedRateString} / {@code fixedDelayString} を含む）。
     * 判定できない場合は理由付きの「判定不能」を返し、呼び出し側が安全側（違反）に倒す。</p>
     *
     * @param scheduled スケジュール指定
     * @return 起動間隔、または判定不能の理由
     */
    static Cadence cadenceOf(Scheduled scheduled) {
        if (!scheduled.cron().isBlank()) {
            String cron = scheduled.cron();
            if (Scheduled.CRON_DISABLED.equals(cron)) {
                return Cadence.undecidable("cron = \"-\" で無効化されている");
            }
            String resolved = resolvePlaceholder(cron);
            if (resolved == null) {
                return Cadence.undecidable("cron = \"" + cron + "\" が既定値の無いプレースホルダである");
            }
            Duration interval = minimumCronInterval(resolved, resolveZone(scheduled.zone()));
            if (interval == null) {
                return Cadence.undecidable("cron 式 \"" + resolved + "\" を解釈できない");
            }
            return Cadence.of(interval, "cron = \"" + resolved + "\"");
        }
        ChronoUnit unit = scheduled.timeUnit().toChronoUnit();
        if (scheduled.fixedRate() >= 0) {
            return Cadence.of(Duration.of(scheduled.fixedRate(), unit),
                "fixedRate = " + scheduled.fixedRate());
        }
        if (scheduled.fixedDelay() >= 0) {
            return Cadence.of(Duration.of(scheduled.fixedDelay(), unit),
                "fixedDelay = " + scheduled.fixedDelay());
        }
        if (!scheduled.fixedRateString().isBlank()) {
            return cadenceOfDurationString(scheduled.fixedRateString(), unit, "fixedRateString");
        }
        if (!scheduled.fixedDelayString().isBlank()) {
            return cadenceOfDurationString(scheduled.fixedDelayString(), unit, "fixedDelayString");
        }
        return Cadence.undecidable("cron / fixedRate / fixedDelay のいずれも指定されていない");
    }

    /** {@code fixedRateString} / {@code fixedDelayString} を Spring と同じ規則で解釈する。 */
    private static Cadence cadenceOfDurationString(String raw, ChronoUnit unit, String attribute) {
        String resolved = resolvePlaceholder(raw);
        if (resolved == null) {
            return Cadence.undecidable(attribute + " = \"" + raw + "\" が既定値の無いプレースホルダである");
        }
        Duration interval = parseSpringDurationString(resolved, unit);
        if (interval == null) {
            return Cadence.undecidable(attribute + " = \"" + resolved + "\" を解釈できない");
        }
        return Cadence.of(interval, attribute + " = \"" + resolved + "\"");
    }

    /**
     * Spring の {@code fixedRateString} / {@code fixedDelayString} と同じ規則で期間を解釈する。
     *
     * <p>ISO-8601（{@code "PT30S"}）ならそのまま、数値のみなら {@code @Scheduled#timeUnit()}
     * （既定はミリ秒）で解釈する。ShedLock 側とは規則が異なるため別実装にしている
     * （ここを取り違えると「30 分と 30 ミリ秒」を取り違え、比較が丸ごと嘘になる）。</p>
     *
     * @param raw 解決済みの文字列
     * @param unit 数値のみの場合に適用する単位
     * @return 期間。解釈できない場合は {@code null}
     */
    static Duration parseSpringDurationString(String raw, ChronoUnit unit) {
        String value = raw.strip();
        try {
            if (value.startsWith("P") || value.startsWith("-P")) {
                return Duration.parse(value);
            }
            return Duration.of(Long.parseLong(value), unit);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * ShedLock が {@code lockAtMostFor} に受け付ける期間表記を解釈する。
     *
     * <p>ShedLock 6.2.0 の {@code net.javacrumbs.shedlock.spring.aop.StringToDurationConverter}
     * と<b>同一の規則</b>を実装している: ISO-8601（{@code "PT5M"}）か、
     * {@code <数値><単位>}（単位は {@code ns/us/ms/s/m/h/d}、<b>省略時はミリ秒</b>）。
     * 「単位省略＝ミリ秒」を取り違えると {@code "300000"} を 300000 分と読んでしまい、
     * 実際には不足しているロックを合格と判定する（＝番人が静かに嘘をつく）。</p>
     *
     * @param raw 解決済みの文字列
     * @return 期間。解釈できない場合は {@code null}
     */
    static Duration parseShedLockDuration(String raw) {
        String value = raw.strip();
        try {
            if (ISO_8601_DURATION.matcher(value).matches()) {
                return Duration.parse(value);
            }
            Matcher matcher = SIMPLE_DURATION.matcher(value);
            if (!matcher.matches()) {
                return null;
            }
            ChronoUnit unit = SHEDLOCK_UNITS.get(matcher.group(2).toLowerCase(Locale.ROOT));
            if (unit == null) {
                return null;
            }
            return Duration.of(Long.parseLong(matcher.group(1)), unit);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * cron 式の<b>最小</b>発火間隔を求める。
     *
     * <p>cron 式を自前で構文解析すると、{@code 0 0/30 * * * *} のような刻み記法や
     * 曜日・月末指定を取りこぼして「間隔が長い」と誤判定しやすい（＝違反の見逃し）。
     * よって Spring 本体の {@link CronExpression} に実際の発火時刻を列挙させ、
     * 隣り合う発火の差の最小値を採る。1 日に複数回発火する形（{@code 0 0,30 3 * * *}）で
     * 最長の間隔を採ると危険な方を見逃すため、必ず<b>最小</b>を採る。</p>
     *
     * <p>探索は 5,000 回または 400 日の窓で打ち切る
     * （月次・曜日指定を 1 周させるには十分で、毎秒起動のような式でも最小間隔は
     * 最初の数回で確定する）。</p>
     *
     * @param cron cron 式（プレースホルダ解決済み）
     * @param zone 評価に用いるタイムゾーン
     * @return 最小発火間隔。解釈できない・発火しない場合は {@code null}
     */
    static Duration minimumCronInterval(String cron, ZoneId zone) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron.strip());
        } catch (RuntimeException ex) {
            return null;
        }
        ZonedDateTime start = CRON_PROBE_START.atZone(zone);
        ZonedDateTime limit = start.plus(CRON_PROBE_HORIZON);
        ZonedDateTime current = expression.next(start);
        if (current == null) {
            return null;
        }
        Duration minimum = null;
        for (int i = 0; i < CRON_PROBE_MAX_FIRINGS && current.isBefore(limit); i++) {
            ZonedDateTime next = expression.next(current);
            if (next == null) {
                break;
            }
            Duration gap = Duration.between(current, next);
            if (minimum == null || gap.compareTo(minimum) < 0) {
                minimum = gap;
            }
            current = next;
        }
        return minimum;
    }

    /**
     * {@code ${prop}} / {@code ${prop:default}} を「コード上から読める値」に解決する。
     *
     * @param raw 注釈に書かれた生の値
     * @return プレースホルダでなければそのまま、既定値付きなら既定値、既定値が無ければ {@code null}
     */
    static String resolvePlaceholder(String raw) {
        String value = raw.strip();
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(2);
    }

    /** {@code @Scheduled#zone()} を解決する（未指定・解決不能なら UTC で評価を固定する）。 */
    private static ZoneId resolveZone(String zone) {
        String resolved = resolvePlaceholder(zone == null ? "" : zone);
        if (resolved == null || resolved.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(resolved);
        } catch (RuntimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    /** 違反メッセージ用に期間を人間可読へ整形する。 */
    private static String format(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % 86_400 == 0) {
            return (seconds / 86_400) + " 日";
        }
        if (seconds % 3_600 == 0) {
            return (seconds / 3_600) + " 時間";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + " 分";
        }
        return seconds + " 秒";
    }

    /**
     * 1 つの {@code @Scheduled} から求めた起動間隔、または判定不能の理由。
     *
     * @param interval 起動間隔（判定不能なら {@code null}）
     * @param description 違反メッセージに載せるスケジュール指定の説明
     * @param undecidableReason 判定不能の理由（判定できたなら {@code null}）
     */
    record Cadence(Duration interval, String description, String undecidableReason) {

        static Cadence of(Duration interval, String description) {
            return new Cadence(interval, description, null);
        }

        static Cadence undecidable(String reason) {
            return new Cadence(null, null, reason);
        }
    }

    /**
     * 当該メソッドがスケジュール実行されるか（{@code @Repeatable} コンテナ込み）。
     *
     * <p>{@link Scheduled} は {@code @Repeatable(Schedules.class)} であるため、
     * 1 メソッドに 2 つ以上指定されると javac は {@code @Scheduled} を直接付けず
     * {@link Schedules} コンテナだけを出力する。両方を見ないと
     * 「複数スケジュールを持つバッチ」が丸ごと番人の対象外になる。</p>
     *
     * @param method 検査対象メソッド
     * @return スケジュール実行されるなら {@code true}
     */
    static boolean isScheduled(JavaMethod method) {
        return method.isAnnotatedWith(Scheduled.class) || method.isAnnotatedWith(Schedules.class);
    }

    /**
     * {@code @SchedulerLock} も {@code @PodLocalScheduled} も無い {@code @Scheduled} を違反として返す。
     *
     * @param method 検査対象メソッド
     * @return 違反の説明文リスト（空なら合格）
     */
    static List<String> findMissingSchedulerLock(JavaMethod method) {
        if (!isScheduled(method)) {
            return List.of();
        }
        if (method.isAnnotatedWith(SchedulerLock.class)) {
            return List.of();
        }
        if (method.isAnnotatedWith(PodLocalScheduled.class)) {
            // Pod ローカル実行が設計意図である旨を監査済みマーカーで宣言済み。
            // マーカー自体の付与要件（理由の記述・Javadoc・付与先）は
            // 二次番人 BatchMarkerAnnotationGuardTest が別途検証する。
            return List.of();
        }
        return List.of(String.format(
            "スケジュールされたメソッド %s に @SchedulerLock がありません。"
                + "本番は複数 Pod で動くため、このままでは Pod 数だけ同時に走ります"
                + "（通知・課金・集計の多重実行）。"
                + "@SchedulerLock(name = \"...\", lockAtMostFor = \"PT...\") を併記してください。"
                + "Pod ごとに走ることが設計意図である場合に限り、"
                + "@PodLocalScheduled(\"<ロックを掛けると何が壊れるか>\") で明示してください。(%s)",
            method.getFullName(), method.getSourceCodeLocation()));
    }

    /**
     * {@code lockAtMostFor} を明示していない {@code @SchedulerLock} を違反として返す。
     *
     * <p>属性値まで見る判定であるため {@code beAnnotatedWith} では表現できず、
     * {@code getAnnotationOfType} で実際の属性値を読む必要がある。</p>
     *
     * @param method 検査対象メソッド
     * @return 違反の説明文リスト（空なら合格）
     */
    static List<String> findMissingLockAtMostFor(JavaMethod method) {
        if (!method.isAnnotatedWith(SchedulerLock.class)) {
            return List.of();
        }
        SchedulerLock lock = method.getAnnotationOfType(SchedulerLock.class);
        if (!lock.lockAtMostFor().isBlank()) {
            return List.of();
        }
        return List.of(String.format(
            "%s の @SchedulerLock(name = \"%s\") に lockAtMostFor がありません。"
                + "未指定は @EnableSchedulerLock の既定値 30m への暗黙依存になります。"
                + "このバッチの最大実行時間を見積もり、lockAtMostFor = \"PT...\" を明示してください"
                + "（短すぎると処理中に他 Pod が二重起動し、長すぎると Pod 異常終了時に"
                + "その時間だけバッチが停止します）。(%s)",
            method.getFullName(), lock.name(), method.getSourceCodeLocation()));
    }

    /**
     * {@code @BatchEndpoint} も {@code @BatchEndpointExempt} も無い {@code @Scheduled} を違反として返す。
     *
     * @param method 検査対象メソッド
     * @return 違反の説明文リスト（空なら合格）
     */
    static List<String> findMissingBatchEndpoint(JavaMethod method) {
        if (!isScheduled(method)) {
            return List.of();
        }
        if (method.isAnnotatedWith(BatchEndpoint.class)) {
            return List.of();
        }
        if (method.isAnnotatedWith(BatchEndpointExempt.class)) {
            return List.of();
        }
        return List.of(String.format(
            "スケジュールされたメソッド %s に @BatchEndpoint がありません。"
                + "このままでは名前で起動できず実行履歴も残らないため、実機検証も障害調査もできません。"
                + "@BatchEndpoint(name = \"{domain}-{action}[-{cadence}]\", description = \"...\") を"
                + "併記してください。数秒間隔の高頻度ワーカーのように履歴が有害な場合に限り、"
                + "@BatchEndpointExempt(\"<起動間隔と、履歴を書いた場合の害>\") で明示してください。(%s)",
            method.getFullName(), method.getSourceCodeLocation()));
    }

    /**
     * {@code @SchedulerLock} 付きメソッドのうちプリミティブ型を返すものを違反として返す。
     *
     * <p>ShedLock はロック対象メソッドの戻り値をプロキシで包む都合上、プリミティブ型
     * （{@code int}/{@code long}/{@code boolean} 等）を返すメソッドをロックできず、
     * {@code LockingNotSupportedException} を実行のたびに投げる。<b>{@code void} は除外する</b>
     * ——素朴な {@code void.class.isPrimitive()} は Java の仕様上 {@code true} を返すが、
     * {@code void} は「値を返さない」だけで ShedLock のプロキシ生成を妨げない
     * （実際、本番の {@code @SchedulerLock} 付きメソッドの大半は {@code void} で問題なく動いている）。
     * ここを弾いてしまうと本番の大半が誤検出になる（issue #2724 是正時に実測で判明した）。
     * 参照型（{@code Integer} 等）も問題ないため違反にしない。</p>
     *
     * @param method 検査対象メソッド
     * @return 違反の説明文リスト（空なら合格）
     */
    static List<String> findPrimitiveReturningSchedulerLock(JavaMethod method) {
        if (!method.isAnnotatedWith(SchedulerLock.class)) {
            return List.of();
        }
        JavaClass returnType = method.getRawReturnType();
        if (returnType.isEquivalentTo(void.class) || !returnType.isPrimitive()) {
            return List.of();
        }
        return List.of(String.format(
            "%s は @SchedulerLock 付きでありながらプリミティブ型 \"%s\" を返しています。"
                + "ShedLock はプリミティブ戻り値のメソッドをロックできず、"
                + "LockingNotSupportedException: Can not lock method returning primitive value を投げて"
                + "@Scheduled 実行のたびに必ず失敗します（一度も実行されません）。"
                + "戻り値が不要なら void に、件数などの可観測性が必要なら log.info で出すか"
                + "参照型（Integer 等）に変更してください。(%s)",
            method.getFullName(), returnType.getName(), method.getSourceCodeLocation()));
    }

    // ══════════════════════════════════════════════════════════════════
    // ArchUnit 条件・述語
    // ══════════════════════════════════════════════════════════════════

    /**
     * {@code @Scheduled}（{@link Schedules} コンテナ込み）が付いたメソッドを選ぶ述語。
     *
     * <p>{@code areAnnotatedWith(Scheduled.class)} を使わないのは、{@code @Repeatable} により
     * 複数指定時に {@code @Scheduled} が直接は付かなくなるためである（上位 Javadoc 参照）。</p>
     */
    private static DescribedPredicate<JavaMethod> areScheduled() {
        return new DescribedPredicate<>("annotated with @Scheduled (including @Schedules container)") {
            @Override
            public boolean test(JavaMethod method) {
                return isScheduled(method);
            }
        };
    }

    /** 判定ヘルパの戻り値（違反の説明文リスト）をそのまま ArchUnit の違反として報告する条件。 */
    private static ArchCondition<JavaMethod> satisfy(
            String description, Function<JavaMethod, List<String>> detector) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<String> violations = new ArrayList<>(detector.apply(method));
                for (String violation : violations) {
                    events.add(SimpleConditionEvent.violated(method, violation));
                }
            }
        };
    }
}
