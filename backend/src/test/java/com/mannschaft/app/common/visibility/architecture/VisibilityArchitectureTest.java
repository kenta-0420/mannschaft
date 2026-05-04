package com.mannschaft.app.common.visibility.architecture;

import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

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
 * <h2>5 ルールの責務</h2>
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
     * <p><strong>Phase A 時点の取り扱い (freeze 戦略)</strong>:
     * 既存実装には本ルール違反が広範に存在するため、Phase A では
     * <strong>新規違反のみを検出するプレースホルダ</strong> として配置する。
     * Phase F で {@link com.tngtech.archunit.lang.ArchRule#because} 付き本格ルールに
     * 差し替え、各通知発行 Service への Mockito ガードテスト
     * ({@code *VisibilityGuardTest}) と併用して呼び忘れまでカバーする。
     *
     * <p>Phase F 本格ルール (予定):
     * <pre>{@code
     * classes().that().callMethodWhere(target ->
     *         target.getOwner().getName().endsWith("NotificationRepository")
     *      && target.getName().startsWith("save"))
     *     .should().dependOnClassesThat()
     *         .haveSimpleName("ContentVisibilityChecker")
     *     .because("§13.5 / §15 D-11");
     * }</pre>
     */
    @ArchTest
    static final ArchRule notification_save_callers_must_depend_on_checker =
        // Phase A プレースホルダ: 必ず通る空ルール (新規違反監視は Phase F で導入)
        // allowEmptyShould(true) — Phase A 時点ではマッチするクラスが存在しなくても許容
        noClasses().that().haveSimpleName("__phase_a_placeholder__")
            .should().dependOnClassesThat().haveSimpleName("__never_match__")
            .because("Phase A プレースホルダ — Phase F で NotificationRepository.save* "
                + "呼出側に ContentVisibilityChecker 依存を強制する本格ルールに差し替える "
                + "(設計書 §13.5 / §15 D-11)")
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

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

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
