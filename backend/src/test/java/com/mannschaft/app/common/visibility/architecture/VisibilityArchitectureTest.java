package com.mannschaft.app.common.visibility.architecture;

import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F00 共通可視性基盤の ArchUnit ルール群。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §13.5
 * (ArchUnit ルール + ガードテスト) / §15 D-12 / §15 D-16。
 *
 * <p>本テストは静的依存検査のみを担う。「メソッド本体での呼び忘れ」検出は
 * Phase F で各通知発行 Service に対して配置する Mockito ガードテスト
 * ({@code *VisibilityGuardTest}) が担当する (§13.5)。
 *
 * <h2>6 ルールの責務</h2>
 * <ol>
 *   <li>{@link #mappers_have_no_external_dependencies}
 *       — {@code common.visibility.mapping} は機能側 enum と {@code java..}
 *         以外に依存しない (Mapper は純粋関数で Repository / Spring Bean に依存禁止 §5.4)
 *   </li>
 *   <li>{@link #common_visibility_does_not_depend_on_features}
 *       — {@code common.visibility} は機能側 service には依存してはならない
 *         (循環依存の防止)
 *   </li>
 *   <li>{@link #notification_save_callers_must_depend_on_checker}
 *       — 通知 Repository {@code save*} 呼出側は {@code ContentVisibilityChecker}
 *         に依存しなければならない (Phase F で本格化、現在は freeze 戦略のプレースホルダ)
 *   </li>
 *   <li>{@link #phase2_reserved_reference_types_unused}
 *       — Phase 2 予約 ReferenceType ({@code PERSONAL_TIMETABLE} / {@code FOLLOW_LIST})
 *         は Phase 1 のコードから参照禁止 (§16.2 / §15 D-12)
 *   </li>
 *   <li>{@link #resolvers_dont_inject_other_resolvers}
 *       — Resolver は他 Resolver を直接 inject せず {@code ContentVisibilityChecker}
 *         経由とする (§15 D-16 循環参照対策)
 *   </li>
 *   <li>{@link #abstractContentVisibilityResolver_subclasses_must_not_be_transactional}
 *       — {@link AbstractContentVisibilityResolver} のサブクラスは
 *         {@code @Transactional} を付与してはならない
 *         (CGLIB プロキシ + final テンプレートメソッドで NPE。PR#320/321 再発防止)
 *   </li>
 * </ol>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class VisibilityArchitectureTest {

    /**
     * Mapper パッケージは機能側 enum と {@code java..} 以外に依存禁止。
     *
     * <p>設計書 §5.4: Mapper は「状態を持たず、副作用も持たない。Repository / DB /
     * Spring Bean には依存しない」純粋関数 util クラス。
     *
     * <p>許可する依存先 package:
     * <ul>
     *   <li>{@code java..} — 標準ライブラリ</li>
     *   <li>{@code ..common.visibility..} — {@link com.mannschaft.app.common.visibility.StandardVisibility} 等</li>
     *   <li>機能側 enum を含むパッケージ (現状 17 Mapper の import 元)</li>
     * </ul>
     *
     * <p>新たに Mapper を追加する場合は許可リストに該当 package を追加する。
     */
    @ArchTest
    static final ArchRule mappers_have_no_external_dependencies =
        classes().that().resideInAPackage("..common.visibility.mapping..")
            .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                    "java..",
                    "..common.visibility..",
                    // 以下、各 Mapper が機能側 enum を import する package
                    "..activity..",
                    "..cms..",
                    "..committee.entity..",
                    "..event..",
                    "..gallery..",
                    "..jobmatching.enums..",
                    "..matching..",
                    "..member..",
                    "..notification.confirmable.entity..",
                    "..actionmemo.enums..",
                    "..todo..",
                    "..recruitment..",
                    "..schedule..",
                    "..survey..",
                    "..timetable..",
                    "..tournament..");

    /**
     * {@code common.visibility} パッケージは機能側 {@code *.service} に依存禁止。
     *
     * <p>設計書 §13.5: 共通基盤が機能側 Service に依存すると循環依存の温床になる。
     * 必要なデータは Repository projection で取得する (§7.6)。
     */
    @ArchTest
    static final ArchRule common_visibility_does_not_depend_on_features =
        noClasses().that().resideInAPackage("..common.visibility..")
            .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "..cms.service..",
                    "..event.service..",
                    "..activity.service..",
                    "..tournament.service..",
                    "..recruitment.service..",
                    "..gallery.service..",
                    "..jobmatching.service..",
                    "..survey.service..",
                    "..matching.service..",
                    "..committee.service..",
                    "..actionmemo.service..",
                    "..schedule.service..",
                    "..timetable.service..",
                    "..member.service..",
                    "..todo.service..",
                    "..notification.service..");

    /**
     * 通知 Repository の {@code save*} 呼出側は {@code ContentVisibilityChecker}
     * 必須。
     *
     * <p>設計書 §13.5 / §15 D-11: 通知配信における可視性確認漏れを防ぐ。
     *
     * <p><strong>Phase F (2026-05-04) で本格ルールへ昇格済</strong>:
     * 本ファイルの本ルールは Phase A プレースホルダから「Phase F 完了済」を
     * 表すマーカーとして残す (空通過)。実体ルールは
     * {@link NotificationVisibilityArchitectureTest} に移管。
     *
     * <p>新しい通知発行 Service を追加する場合、
     * {@link NotificationVisibilityArchitectureTest#notification_repository_save_callers_must_depend_on_checker}
     * が違反として検出する。中央集約された
     * {@code NotificationHelper} / {@code NotificationService} 経由が推奨経路。
     */
    @ArchTest
    static final ArchRule notification_save_callers_must_depend_on_checker =
        // Phase F で実体ルールは NotificationVisibilityArchitectureTest に移管。
        // ここはマーカーとして残し、設計書からの参照可能性を維持する。
        noClasses().that().haveSimpleName("__phase_f_completed_marker__")
            .should().dependOnClassesThat().haveSimpleName("__never_match__")
            .because("Phase F 完了マーカー — 実体は NotificationVisibilityArchitectureTest "
                + "(notification_repository_save_callers_must_depend_on_checker) に移管済 "
                + "(設計書 §13.5 / §15 D-11 / §19.3)")
            .allowEmptyShould(true);

    /**
     * Phase 2 予約 ReferenceType ({@code PERSONAL_TIMETABLE} / {@code FOLLOW_LIST})
     * を Phase 1 のコードから参照禁止。
     *
     * <p>設計書 §16.2 / §15 D-12: Phase 2 予約は enum 値として先行定義済みだが
     * Resolver 未実装のため fail-closed。Phase 1 で参照すると常に false 返却となり
     * 利用者に意図しないアクセス拒否が発生する。
     *
     * <p>カスタム {@link ArchCondition} で enum 値の参照（メソッド呼び出し
     * {@code ReferenceType.PERSONAL_TIMETABLE} / {@code ReferenceType.FOLLOW_LIST}
     * を含む field access）を検出する。
     *
     * <p>ただしテスト・本基盤自身 (ReferenceType enum 定義そのもの) は対象外。
     */
    @ArchTest
    static final ArchRule phase2_reserved_reference_types_unused =
        noClasses().that().resideInAPackage("com.mannschaft.app..")
            .and().resideOutsideOfPackage("..common.visibility..")
            .should(referencePhase2ReservedReferenceTypes())
            .because("§16.2 / §15 D-12 — Phase 2 予約 ReferenceType は Phase 1 で "
                + "Resolver 未実装のため fail-closed。参照すると常にアクセス拒否となる");

    /**
     * Resolver は他 Resolver を直接 inject 禁止。
     *
     * <p>設計書 §15 D-16: 循環依存と評価ループを避けるため、Resolver は
     * {@link ContentVisibilityResolver} 型のフィールドを持たず、必要に応じて
     * {@code ContentVisibilityChecker} を inject する。再帰呼び出しは深度上限 3。
     *
     * <p>本ルールでは「Resolver 実装クラス自身が他の Resolver 実装クラスに依存
     * してはならない」を検査する。フィールドや constructor 引数の型に
     * {@link ContentVisibilityResolver} 派生型を持つ場合は違反となる。
     *
     * <p>ただし本基盤側のクラス
     * ({@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} 等) は
     * Resolver の List を受け取る正規ルートなので {@code ..common.visibility..}
     * 配下は対象外。
     */
    @ArchTest
    static final ArchRule resolvers_dont_inject_other_resolvers =
        // allowEmptyShould(true) — Phase A 時点では Resolver 実装クラスが未登場
        // (A-1c で AbstractContentVisibilityResolver、Phase B 以降で各機能の Resolver 実装)。
        // クラスが存在しない段階でも本ルール定義を main にマージしておき、
        // Phase B 以降の足軽が Resolver 実装を追加した瞬間から自動的に検査が有効になる。
        noClasses().that().areAssignableTo(ContentVisibilityResolver.class)
            .and().areNotInterfaces()
            .and().resideOutsideOfPackage("..common.visibility..")
            .should(dependOnOtherResolverImplementations())
            .because("§15 D-16 — Resolver 同士の直接 inject は循環参照を招く。"
                + "他 type の判定が必要なら ContentVisibilityChecker 経由とすること")
            .allowEmptyShould(true);

    /**
     * {@link AbstractContentVisibilityResolver} のサブクラスは
     * {@code @Transactional} を付与してはならない。
     *
     * <p><strong>背景</strong> (PR#320 Event / PR#321 Schedule で発覚した CGLIB プロキシ NPE):
     * <ul>
     *   <li>{@code @Transactional} 付き Bean は Spring が CGLIB でプロキシ化する
     *       (Objenesis でコンストラクタ bypass — フィールド初期化なし)</li>
     *   <li>{@link AbstractContentVisibilityResolver#canView} /
     *       {@link AbstractContentVisibilityResolver#filterAccessible} は
     *       {@code final} テンプレートメソッド。CGLIB は {@code final} メソッドを
     *       上書き (override) できない</li>
     *   <li>結果: proxy インスタンス (フィールド null) 上で親クラスの本物の
     *       実装が走り、{@code Repository} / {@code MembershipBatchQueryService} が
     *       {@code null} 参照されて NPE</li>
     * </ul>
     *
     * <p><strong>解決方針</strong>: トランザクションは下層
     * ({@code Repository} / {@code MembershipBatchQueryService} 等) が自前で
     * 持つため Resolver 側に {@code @Transactional} は不要。Resolver から
     * {@code @Transactional} を外せば CGLIB プロキシ化されず、
     * {@code final} テンプレートが本物のサブクラスインスタンス上で動く。
     *
     * <p>本ルールは Phase C/D で実装する 11 機能の Resolver で同型 NPE を
     * 機械的に防ぐ。基底クラス自身は対象外。
     */
    @ArchTest
    static final ArchRule abstractContentVisibilityResolver_subclasses_must_not_be_transactional =
        // allowEmptyShould(true) — Phase A 時点ではサブクラスが未登場の場面でも
        // ルール定義を main にマージできるようにする。Phase B 以降にサブクラスが
        // 追加された瞬間から自動で検査が有効になる。
        classes().that().areAssignableTo(AbstractContentVisibilityResolver.class)
            .and(notAbstractContentVisibilityResolverItself())
            .should().notBeAnnotatedWith(Transactional.class)
            .because("AbstractContentVisibilityResolver は final テンプレートメソッド "
                + "(canView / filterAccessible) を持つ。@Transactional を付けると "
                + "CGLIB プロキシ化 (Objenesis でコンストラクタ bypass) され、"
                + "final な canView/filterAccessible が proxy インスタンス "
                + "(フィールド null) 上で実行され NPE になる "
                + "(PR#320 Event / PR#321 Schedule で発覚)。"
                + "トランザクションは下層 Repository / MembershipBatchQueryService が"
                + "自前で持つため Resolver には不要")
            .allowEmptyShould(true);

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    /**
     * 「{@link AbstractContentVisibilityResolver} 自身ではない」を表す
     * {@link DescribedPredicate}。
     *
     * <p>{@link #abstractContentVisibilityResolver_subclasses_must_not_be_transactional}
     * で「サブクラスのみを対象にする (基底クラス自身は除外する)」絞り込みに使う。
     */
    private static DescribedPredicate<JavaClass> notAbstractContentVisibilityResolverItself() {
        return new DescribedPredicate<>("are not AbstractContentVisibilityResolver itself") {
            @Override
            public boolean test(JavaClass clazz) {
                return !clazz.getFullName().equals(
                    AbstractContentVisibilityResolver.class.getName());
            }
        };
    }

    /**
     * クラスが Phase 2 予約 ReferenceType ({@code PERSONAL_TIMETABLE} /
     * {@code FOLLOW_LIST}) を参照しているかを検査する {@link ArchCondition}。
     *
     * <p>field access の {@code target.name} を見て、
     * 対象 enum 値名と一致するクラスを違反として報告する。
     */
    private static ArchCondition<JavaClass> referencePhase2ReservedReferenceTypes() {
        return new ArchCondition<>("reference Phase 2 reserved ReferenceType values") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                clazz.getFieldAccessesFromSelf().forEach(access -> {
                    String targetOwner = access.getTarget().getOwner().getFullName();
                    String targetName = access.getTarget().getName();
                    if ("com.mannschaft.app.common.visibility.ReferenceType".equals(targetOwner)
                        && ("PERSONAL_TIMETABLE".equals(targetName)
                         || "FOLLOW_LIST".equals(targetName))) {
                        String message = String.format(
                            "%s references Phase 2 reserved ReferenceType.%s in %s",
                            clazz.getName(), targetName, access.getSourceCodeLocation());
                        events.add(SimpleConditionEvent.violated(access, message));
                    }
                });
                // メソッド呼び出しに混じる引数として渡される場合も検出
                clazz.getMethodCallsFromSelf().forEach((JavaMethodCall call) -> {
                    // 直接の field access ではないがメソッド引数として参照されるケースは
                    // bytecode 上 getstatic として field access に現れるため上記で検出済み。
                    // ここでは future extension 用のフックとして残す。
                });
            }
        };
    }

    /**
     * Resolver 実装クラスが他の Resolver 実装クラスに依存しているかを検査する
     * {@link ArchCondition}。
     *
     * <p>{@link ContentVisibilityResolver} のサブタイプであり、かつ
     * {@code ..common.visibility..} 配下の本基盤クラスではないクラスへの依存を
     * 違反として報告する。
     */
    private static ArchCondition<JavaClass> dependOnOtherResolverImplementations() {
        return new ArchCondition<>("depend on other Resolver implementations") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                clazz.getDirectDependenciesFromSelf().forEach(dep -> {
                    JavaClass target = dep.getTargetClass();
                    if (target.equals(clazz)) {
                        return;
                    }
                    if (!target.isAssignableTo(ContentVisibilityResolver.class)) {
                        return;
                    }
                    String pkg = target.getPackageName();
                    if (pkg.startsWith("com.mannschaft.app.common.visibility")
                        && !pkg.contains(".mapping")) {
                        // 本基盤側の ContentVisibilityResolver インタフェース等は許可
                        return;
                    }
                    String message = String.format(
                        "%s depends on other Resolver %s at %s",
                        clazz.getName(), target.getName(), dep.getSourceCodeLocation());
                    events.add(SimpleConditionEvent.violated(dep, message));
                });
            }
        };
    }
}
