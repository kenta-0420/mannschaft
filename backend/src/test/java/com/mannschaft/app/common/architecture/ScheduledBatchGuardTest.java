package com.mannschaft.app.common.architecture;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.batch.BatchEndpointExempt;
import com.mannschaft.app.common.batch.PodLocalScheduled;
import com.tngtech.archunit.base.DescribedPredicate;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
 * <h2>本番人が固定する 3 つの不変条件</h2>
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
 * </ol>
 *
 * <h2>発足時点の状態（試練＝テスト先行のため red で始まる）</h2>
 * <p>ルール 1 とルール 3 は<b>発足時点で違反が残っている</b>。これは意図した状態である。
 * 受け入れ条件から先に失敗するテストを置き（試練）、実装＝既存バッチの是正（Phase 1-c / Phase 3）で
 * green 化する、というプロジェクトの開発フローに従っている。
 * ルール 2 は発足時点で違反 0 件（green 発足）であり、目的は<b>新規流入の防止</b>である。</p>
 *
 * <h2>green 発足するルールの「偽陰性ゼロ」証明</h2>
 * <p>「違反 0 件」は<b>番人が動いていることの証明にはならない</b> ——
 * 判定ロジックが常に空リストを返す壊れ方をしていても、本番走査は緑のままだからである。
 * よって本番人の判定ロジック（{@link #findMissingSchedulerLock}、
 * {@link #findMissingLockAtMostFor}、{@link #findMissingBatchEndpoint}）は
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

    // ══════════════════════════════════════════════════════════════════
    // 判定ロジック（メタテストと共有する単一正準）
    // ══════════════════════════════════════════════════════════════════

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
