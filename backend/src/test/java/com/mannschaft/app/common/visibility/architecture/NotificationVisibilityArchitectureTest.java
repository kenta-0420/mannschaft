package com.mannschaft.app.common.visibility.architecture;

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

/**
 * F00 Phase F: 通知発行 Service が {@code ContentVisibilityChecker} に
 * 依存することを強制する ArchUnit ルール群。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §11.1 (Mention・通知配信での Resolver 必須利用) / §13.5 (ArchUnit ルール) /
 * §15 D-11 (通知配信での Resolver 利用は ArchUnit + ガードテストで強制)。
 *
 * <p>本テストは {@link VisibilityArchitectureTest} の Phase A プレースホルダ
 * ({@code notification_save_callers_must_depend_on_checker}) を本格ルールに
 * 昇格させたもの。Phase A 時点では既存実装に広範な漏れがあったため
 * placeholder で逃がしていたが、Phase F で改修済の 3 中央 Service
 * ({@code NotificationService} / {@code NotificationDispatchService} /
 * {@code NotificationHelper}) に対して恒久的なガードレールを設置する。
 *
 * <h2>2 ルールの責務</h2>
 * <ol>
 *   <li>{@link #notification_repository_save_callers_must_depend_on_checker} —
 *       {@code com.mannschaft.app.notification.repository.NotificationRepository}
 *       の {@code save*} メソッドを呼び出すクラスは
 *       {@code ContentVisibilityChecker} に依存しなければならない</li>
 *   <li>{@link #notification_dispatch_service_must_depend_on_checker} —
 *       {@code NotificationDispatchService} は {@code ContentVisibilityChecker}
 *       に依存しなければならない (配信前 二重防御の不可欠条件)</li>
 * </ol>
 *
 * <p><strong>Phase B 以降の運用</strong>: 新しい通知発行 Service を追加する
 * 場合、本ルール違反となるため必ず {@code ContentVisibilityChecker} を inject
 * して {@code canView} / {@code filterAccessible} を通すこと。
 * 中央集約された 3 中央 Service ({@code NotificationHelper} 等) を経由する
 * のが推奨経路 (これらは既に visibility ガード済)。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class NotificationVisibilityArchitectureTest {

    /** 本基盤で対象とする {@code NotificationRepository} の完全修飾名. */
    private static final String NOTIFICATION_REPOSITORY_FQN =
            "com.mannschaft.app.notification.repository.NotificationRepository";

    /** 本基盤で対象とする {@code ContentVisibilityChecker} の完全修飾名. */
    private static final String CHECKER_FQN =
            "com.mannschaft.app.common.visibility.ContentVisibilityChecker";

    /**
     * {@code NotificationRepository.save*} を呼び出すクラスは
     * {@code ContentVisibilityChecker} に依存しなければならない。
     *
     * <p>設計書 §13.5 / §15 D-11: 通知発行における可視性確認漏れを防ぐ
     * 恒久ガードレール。Phase F 改修で {@code NotificationService} のみが
     * 唯一の {@code save} 呼出元となったため、今後新たに
     * {@code NotificationRepository} を直接書き込む Service を追加した場合
     * 本ルールが違反として検出する。
     *
     * <p>例外として {@code com.mannschaft.app.common.visibility} 配下のクラス
     * 自身は対象外 (Phase B 以降で Resolver が NotificationProjection 等を
     * 介して通知 Repository に触る可能性に備えた予約)。
     */
    @ArchTest
    static final ArchRule notification_repository_save_callers_must_depend_on_checker =
        classes().that(callMethodOnNotificationRepositorySave())
            .and().resideOutsideOfPackage("..common.visibility..")
            .should(dependOnContentVisibilityChecker())
            .because("§13.5 / §15 D-11 — 通知発行 Service は ContentVisibilityChecker "
                + "経由で受信者の可視性をガードしなければならない");

    /**
     * {@code NotificationDispatchService} は {@code ContentVisibilityChecker}
     * に依存しなければならない。
     *
     * <p>配信直前の二重防御 (DB から復元した古い通知の再送等で createNotification
     * を経由しない経路をも守る) を強制するための個別ルール。
     */
    @ArchTest
    static final ArchRule notification_dispatch_service_must_depend_on_checker =
        classes().that().haveFullyQualifiedName(
                "com.mannschaft.app.notification.service.NotificationDispatchService")
            .should(dependOnContentVisibilityChecker())
            .because("§11.1 — 配信直前の二重防御として ContentVisibilityChecker "
                + "に依存し sourceType→ReferenceType→canView のガードを行う必要がある");

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    /**
     * {@link NotificationRepository#save} 系メソッドを呼ぶクラスを抽出する
     * {@link com.tngtech.archunit.base.DescribedPredicate}。
     *
     * <p>{@link com.tngtech.archunit.base.DescribedPredicate} を直接実装する
     * 代わりにラムダで簡潔に書く。
     */
    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass>
            callMethodOnNotificationRepositorySave() {
        return new com.tngtech.archunit.base.DescribedPredicate<>(
                "call NotificationRepository.save*") {
            @Override
            public boolean test(JavaClass clazz) {
                for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                    String ownerName = call.getTarget().getOwner().getFullName();
                    String methodName = call.getTarget().getName();
                    if (NOTIFICATION_REPOSITORY_FQN.equals(ownerName)
                            && methodName.startsWith("save")) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * クラスが {@code ContentVisibilityChecker} に直接依存しているかを
     * 検査する {@link ArchCondition}。
     *
     * <p>field / constructor 引数 / メソッド呼び出しのいずれかに
     * {@code ContentVisibilityChecker} 型が現れれば合格。
     */
    private static ArchCondition<JavaClass> dependOnContentVisibilityChecker() {
        return new ArchCondition<>("depend on " + CHECKER_FQN) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean dependsOnChecker = clazz.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dep -> CHECKER_FQN.equals(dep.getTargetClass().getFullName()));
                if (!dependsOnChecker) {
                    String message = String.format(
                            "%s does not depend on %s (F00 Phase F セキュリティガード違反)",
                            clazz.getName(), CHECKER_FQN);
                    events.add(SimpleConditionEvent.violated(clazz, message));
                }
            }
        };
    }
}
