package com.mannschaft.app.common.architecture;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.service.ScheduleKeepNotificationService;
import com.mannschaft.app.schedule.service.ScheduleKeepService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F03.17（キープ＝日付未定の予定）§9.5 AC-28a/b/c — 「滞留リマインドを実装しない」という
 * 否定の設計判断を、構造的な番人として機械的に保証する（docs/features/F03.17_schedule_keep.md §9.5）。
 *
 * <h2>なぜ E2E ではなく ArchUnit か</h2>
 * <p>「長期間放置しても通知が送られない」は<b>否定の観測</b>であり、E2E では待ち時間をいくら
 * 伸ばしても証明にならない。CLAUDE.md「責めない・急かさない」原則（§1.3）どおり、
 * <b>キープの滞留を催促する通知は実装してはならない</b>という設計判断そのものを、
 * 実行時の振る舞いではなく<b>構造</b>で保証する。</p>
 *
 * <h2>なぜ「{@code ScheduleKeep} 型への依存」を軸にするか</h2>
 * <p>核 {@code NotificationType} enum は §6.2 の裁定により改変しない（schedule ドメイン独自の
 * 文字列種別 {@code SCHEDULE_KEEP_CONVERTED} 等を使う）。よって enum を監視しても滞留リマインドの
 * 実装を検知できない（素通りする番人になる）。{@code ScheduleKeepEntity} /
 * {@code ScheduleKeepRepository} / {@code ScheduleKeepService} への依存を軸にすれば、
 * 通知種別の表現方法に関係なく捕まえられる。</p>
 *
 * <h2>なぜ全パッケージ横断か</h2>
 * <p>{@code com.mannschaft.app.schedule} 配下に限定すると、滞留リマインドを notification ドメインや
 * 共通バッチ基盤に置かれた場合に素通りしてしまう。「どこに置かれても {@code ScheduleKeep} を
 * 参照した時点で赤」にするのが正しい。</p>
 *
 * <h2>凍結（{@code FreezingArchRule}）を使わない理由</h2>
 * <p>本ルールは違反ゼロから始まるので凍結の必要が無く、凍結すると将来の違反が「既存扱い」で
 * 素通りしかねない（F03.17 §9.5）。</p>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ScheduleKeepNoReminderArchTest {

    /**
     * AC-28c で許可する「通知発行かつ ScheduleKeep 系型に依存してよい」実装クラスの許可リスト。
     *
     * <p>F03.17 §6.1 が通知する契機として認めているのは「変換された」（実装済み・
     * {@link ScheduleKeepNotificationService#notifyConverted}）のみである。「作成された」通知は
     * Wave2 では未配線（§6.2「スコープ内 MEMBER 全員への配信は Wave2 では実装していない」）のため、
     * 現時点の許可リストはこの実装経路を構成する2クラスで正しい。将来「作成された」通知を
     * 配線する際は、本許可リストへの追記と本 Javadoc の更新をセットで行うこと。</p>
     *
     * <p><b>{@link ScheduleKeepService} も許可リストに含める理由</b>: {@link #SCHEDULE_KEEP_CORE_TYPE_NAMES}
     * に {@code ScheduleKeepNotificationService} を加えた結果（穴1の是正）、
     * {@code ScheduleKeepNotificationService} を呼ぶクラスは自動的に「ScheduleKeep 系の型に依存する」
     * を満たすようになった。{@code ScheduleKeepService.convert()} は §6.1 で許可された変換通知を
     * 発火させるために {@code ScheduleKeepNotificationService.notifyConverted()} を呼ぶ、
     * <b>正規の実装経路の一部</b>であり、これも許可リストに載せなければ実測で false positive
     * になることを確認した（AC-28c が ScheduleKeepService 自身を 70 件の依存で違反として
     * 検出した。ScheduleKeepEntity/Repository への依存はキープ本体の CRUD として当然であり、
     * 滞留リマインドとは無関係）。</p>
     *
     * <h2>⚠️ 残存リスク（構造的には塞げない・人によるレビュー必須）</h2>
     * <p>{@link ScheduleKeepNotificationService} と {@link ScheduleKeepService} は許可リストに
     * 載っているため、<b>この2クラス自身の中に</b>滞留リマインドを実装し、かつ {@code @Scheduled} も
     * {@code @BatchEndpoint} も付けない（例: 既存の許可されたトリガー——変換処理からの
     * 呼び出しに相乗りする、イベントリスナーから周期的に呼ぶ等）場合、AC-28a/28b/28c の
     * いずれも検知できない。AC-28c は許可リストで自身を除外しており、AC-28a/28b は
     * {@code @Scheduled}/{@code @BatchEndpoint} の有無を軸にしているため、
     * どちらの軸にも乗らないコードは原理的に見えない。
     * <b>この許可リストに載る2クラスへメソッドを追加する際は、それが滞留リマインド（催促通知）で
     * ないことを人間がレビューで確認すること。</b> 本番人はあくまで「型依存」という
     * 一つの軸で機械的に保証する多層防御の一部であり、許可リスト内部の実装の妥当性は
     * 番人ではなくコードレビューが担う。</p>
     */
    private static final Set<String> ALLOWED_NOTIFIER_CLASS_NAMES = Set.of(
        ScheduleKeepNotificationService.class.getName(),
        ScheduleKeepService.class.getName());

    /**
     * 中核型集合に {@code ScheduleKeepNotificationService} も含める理由【穴1の是正】。
     *
     * <p>当初は {@code ScheduleKeepEntity}/{@code ScheduleKeepRepository}/{@code ScheduleKeepService}
     * の3型のみだったが、それだと「滞留リマインドが {@code ScheduleKeepNotificationService} 経由で
     * 通知する」経路が素通りした——AC-28c の母集団述語は {@code NotificationService} への<b>直接</b>
     * 依存を条件にしており、{@code ScheduleKeepNotificationService} を1段挟むだけで外れてしまう。
     * ArchUnit の {@code dependOnClassesThat} は<b>推移的依存を辿らない</b>ため、AC-28a/28b の
     * 「ScheduleKeep 系型への依存」チェックにも同様に引っかからなかった（実測で確認済み）。
     * {@code ScheduleKeepNotificationService} 自体を中核型集合に加えることで、
     * これを直接依存する側（＝間接的な通知送出クラス）を AC-28a/28b/28c いずれからも捕まえられる。</p>
     */
    private static final Set<String> SCHEDULE_KEEP_CORE_TYPE_NAMES = Set.of(
        ScheduleKeepEntity.class.getName(),
        ScheduleKeepRepository.class.getName(),
        ScheduleKeepService.class.getName(),
        ScheduleKeepNotificationService.class.getName());

    // ------------------------------------------------------------------
    // AC-28a: @Scheduled メソッドを持つクラスは ScheduleKeep 系の型に依存しない
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule scheduled_classes_should_not_depend_on_schedule_keep =
        noClasses().that(haveAScheduledMethod())
            .should().dependOnClassesThat(areScheduleKeepCoreType())
            .because("F03.17 §9.5 AC-28a — キープは日付未定の予定であり、滞留リマインド等の"
                + "時刻起因の自動処理を持たない。@Scheduled を持つクラスが ScheduleKeep 系の型に"
                + "依存した時点で、その設計判断が破られたことを構造的に検知する")
            .as("no @Scheduled class should depend on ScheduleKeep core types (AC-28a)");

    // ------------------------------------------------------------------
    // AC-28b: @BatchEndpoint メソッドを持つクラスは ScheduleKeep 系の型に依存しない
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule batch_endpoint_classes_should_not_depend_on_schedule_keep =
        noClasses().that(haveABatchEndpointMethod())
            .should().dependOnClassesThat(areScheduleKeepCoreType())
            .because("F03.17 §9.5 AC-28b — 滞留リマインドを手動キック可能な @BatchEndpoint 経路"
                + "からも生やしてはならない。@BatchEndpoint を持つクラスが ScheduleKeep 系の型に"
                + "依存した時点で赤にする")
            .as("no @BatchEndpoint class should depend on ScheduleKeep core types (AC-28b)");

    // ------------------------------------------------------------------
    // AC-28c: 通知発行型を呼び ScheduleKeep 系に依存するクラスは許可リストのみ
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule notifier_classes_depending_on_schedule_keep_should_be_allow_listed =
        noClasses().that(callNotificationServiceAndDependOnScheduleKeep())
            .and(isNotAllowListed())
            // 述語 callNotificationServiceAndDependOnScheduleKeep() で絞り込んだ集合は
            // 定義上すでに「ScheduleKeep 系の型に依存する」ため、この should は必ず違反として
            // 報告される。つまり「(通知発行 かつ ScheduleKeep 依存 かつ 許可リスト外)」に
            // 該当した時点で赤、という許可リスト方式をそのまま表現している。
            .should().dependOnClassesThat(areScheduleKeepCoreType())
            .allowEmptyShould(true)
            .because("F03.17 §9.5 AC-28c — 通知を発行する型（NotificationService 等の送信口）を呼び、"
                + "かつ ScheduleKeep 系の型に依存するクラスは §6.1 が許可した変換通知の実装クラス"
                + "（ScheduleKeepNotificationService）のみ。許可リスト外に新しく現れた時点で、"
                + "それは未承認の通知経路（滞留リマインド等）である疑いが強いため赤にする。"
                + "許可リスト方式の性質上、正常状態（違反ゼロ）では母集団が空集合になる。"
                + "allowEmptyShould(true) はその正常系を fail 扱いしないための宣言であり、"
                + "検知力を弱めるものではない（母集団の絞り込みは that() 側の述語が担う）")
            .as("only the allow-listed class may send notifications while depending on ScheduleKeep (AC-28c)");

    // ══════════════════════════════════════════════════════════════════
    // 判定ロジック
    // ══════════════════════════════════════════════════════════════════

    private static boolean isScheduled(JavaMethod method) {
        return method.isAnnotatedWith(Scheduled.class) || method.isAnnotatedWith(Schedules.class);
    }

    private static DescribedPredicate<JavaClass> haveAScheduledMethod() {
        return new DescribedPredicate<>("have a @Scheduled method (including @Schedules container)") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getMethods().stream()
                    .anyMatch(ScheduleKeepNoReminderArchTest::isScheduled);
            }
        };
    }

    private static DescribedPredicate<JavaClass> haveABatchEndpointMethod() {
        return new DescribedPredicate<>("have a @BatchEndpoint method") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getMethods().stream()
                    .anyMatch(method -> method.isAnnotatedWith(BatchEndpoint.class));
            }
        };
    }

    private static DescribedPredicate<JavaClass> areScheduleKeepCoreType() {
        return new DescribedPredicate<>("be a ScheduleKeep core type "
            + "(ScheduleKeepEntity / ScheduleKeepRepository / ScheduleKeepService)") {
            @Override
            public boolean test(JavaClass javaClass) {
                return SCHEDULE_KEEP_CORE_TYPE_NAMES.contains(javaClass.getFullName());
            }
        };
    }

    /**
     * 「{@code NotificationService} <b>または</b> {@code ScheduleKeepNotificationService} を呼び出し、
     * かつ {@code ScheduleKeep} 系の型に依存する」クラス。AC-28c の対象母集団を定義する述語。
     *
     * <p>{@code ScheduleKeepNotificationService} も送信口の入口として扱う理由【穴1の是正】:
     * {@code NotificationService} への直接依存のみを条件にすると、滞留リマインドが
     * {@code ScheduleKeepNotificationService} を1段挟んで通知した場合に素通りする
     * （{@code @Scheduled} が付いていても AC-28a は推移的依存を辿らないため同様に素通りする。
     * 実測で確認済み）。{@code ScheduleKeepNotificationService} 自体は
     * {@link #SCHEDULE_KEEP_CORE_TYPE_NAMES} に含まれるため、これを呼ぶクラスは
     * 「ScheduleKeep 系の型に依存する」を自動的に満たす——したがって、
     * {@code ScheduleKeepNotificationService} を呼ぶクラスは通知発行の条件を満たした時点で
     * 常に母集団に入る。</p>
     */
    private static DescribedPredicate<JavaClass> callNotificationServiceAndDependOnScheduleKeep() {
        return new DescribedPredicate<>(
            "call NotificationService (directly or via ScheduleKeepNotificationService) "
                + "and depend on ScheduleKeep core types") {
            @Override
            public boolean test(JavaClass javaClass) {
                boolean callsNotificationService = javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> {
                        JavaClass target = dependency.getTargetClass();
                        return target.isEquivalentTo(NotificationService.class)
                            || target.isEquivalentTo(ScheduleKeepNotificationService.class);
                    });
                if (!callsNotificationService) {
                    return false;
                }
                return javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency ->
                        SCHEDULE_KEEP_CORE_TYPE_NAMES.contains(dependency.getTargetClass().getFullName()));
            }
        };
    }

    /** F03.17 §6.1 の許可リスト（{@link #ALLOWED_NOTIFIER_CLASS_NAMES}）に載っていないクラス。 */
    private static DescribedPredicate<JavaClass> isNotAllowListed() {
        return new DescribedPredicate<>("not allow-listed (F03.17 §6.1)") {
            @Override
            public boolean test(JavaClass javaClass) {
                return !ALLOWED_NOTIFIER_CLASS_NAMES.contains(javaClass.getFullName());
            }
        };
    }
}
