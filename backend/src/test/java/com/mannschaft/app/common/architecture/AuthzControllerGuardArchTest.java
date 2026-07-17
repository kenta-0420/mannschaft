package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 公開Controllerエンドポイントの認可番人テスト（認可根治戦役 Wave4・案1）。
 *
 * <p>設計指針: 認可漏れ(IDOR)全域監査戦役（Wave0〜Wave3）で「呼び出し元まかせ認可」＝
 * Controller が認可を一切行わず素通しする構造的欠陥が繰り返し検出された
 * （台帳 {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}）。本テストは
 * 「新規に追加される公開エンドポイントが認可シグナルを一切持たない」事故を
 * コンパイル時ではなく <b>CI 静的解析</b> で機械的に検知する最終防衛線として新設する。
 *
 * <h2>検査対象</h2>
 * <p>{@code @RestController} / {@code @Controller} が付与されたクラスに定義された、
 * public かつ Mapping アノテーション
 * （{@code @GetMapping}/{@code @PostMapping}/{@code @PutMapping}/{@code @PatchMapping}/
 * {@code @DeleteMapping}/{@code @RequestMapping}。いずれも {@code @RequestMapping} の
 * メタアノテーションを持つため {@link JavaMethod#isMetaAnnotatedWith(Class)} で一括検出）
 * を持つメソッド（＝公開エンドポイント）。
 *
 * <h2>認可シグナル（いずれか一方があれば合格）</h2>
 * <ol>
 *   <li>(A) メソッド または宣言クラスに {@code @PreAuthorize}
 *       （{@code RetentionPolicy.RUNTIME} のため ArchUnit から検出確実）</li>
 *   <li>(B) メソッド本体が認可呼び出しをしている:
 *       {@code com.mannschaft.app.common.AccessControlService} /
 *       {@code com.mannschaft.app.common.visibility.ContentVisibilityChecker} への
 *       メソッド呼び出し、または単純クラス名が {@code *AccessGuard}
 *       （例: {@code common.security.AccessGuard}, {@code EventScopeAccessGuard},
 *       {@code BulletinAccessGuard}, {@code ReservationViewAccessGuard},
 *       {@code QuickMemoAccessGuard}, {@code FolderScopeAccessGuard} 等）・
 *       {@code *AccessService}（例: {@code MatchAccessService},
 *       {@code VillageBulletinAccessService} 等）で終わるクラスへのメソッド呼び出し。
 *       ホワイトリストは「命名規約に基づく suffix 判定」とし、個別クラスの
 *       ハードコード列挙にしない（新規 AccessGuard/AccessService が追加された瞬間に
 *       自動で認可シグナルとして認識されるようにするため）。</li>
 * </ol>
 *
 * <p><b>直接呼び出しのみ判定</b>（{@link JavaMethod#getMethodCallsFromSelf()}）。
 * 委譲先 Service の内部で認可している推移的なケースは本テストでは拾わない
 * （{@link CrossDomainTransactionalArchTest} と同じ誤検知抑制方針）。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>「Service 層で認可・Controller は素通し」という既存の正当な設計のエンドポイントが
 * 多数存在するため、{@link FreezingArchRule} で既存の非該当エンドポイントを凍結ストア
 * （{@code src/test/resources/archunit_store/}）へ記録し、<b>新規に追加される
 * 認可シグナルなしエンドポイントのみ</b> fail させる。既存の凍結分を解消すると
 * {@code freeze.refreeze=false} のデフォルト挙動でストアが自動縮小される。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class AuthzControllerGuardArchTest {

    private static final String ACCESS_CONTROL_SERVICE_FQN =
        "com.mannschaft.app.common.AccessControlService";
    private static final String CONTENT_VISIBILITY_CHECKER_FQN =
        "com.mannschaft.app.common.visibility.ContentVisibilityChecker";
    private static final String ACCESS_GUARD_SUFFIX = "AccessGuard";
    private static final String ACCESS_SERVICE_SUFFIX = "AccessService";

    @ArchTest
    static final ArchRule public_controller_endpoints_must_have_authorization_signal =
        FreezingArchRule.freeze(
            methods().that(areMappingEndpointsOfControllerClasses())
                .should(haveAnAuthorizationSignal())
                .because("認可根治戦役 Wave4 — 公開Controllerエンドポイント（Mappingメソッド）は "
                    + "@PreAuthorize か、AccessControlService/ContentVisibilityChecker/"
                    + "*AccessGuard/*AccessService への認可呼び出しのいずれかを持つべき。"
                    + "既存の「Service層で認可・Controllerは素通し」EPは凍結し、"
                    + "新規に認可シグナルを持たないEPが追加された場合のみ fail させる")
                // 凍結ストアの照合キー（rule description）を固定する。
                .as("public controller endpoints must have an authorization signal (Wave4)"));

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** 「@RestController/@Controller クラスの public Mapping メソッド」を表す述語。 */
    private static DescribedPredicate<JavaMethod> areMappingEndpointsOfControllerClasses() {
        return new DescribedPredicate<>(
                "are public Mapping-annotated methods of @RestController/@Controller classes") {
            @Override
            public boolean test(JavaMethod method) {
                if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                    return false;
                }
                JavaClass owner = method.getOwner();
                if (!isControllerClass(owner)) {
                    return false;
                }
                return method.isMetaAnnotatedWith(RequestMapping.class);
            }
        };
    }

    /** クラスに {@code @RestController} または {@code @Controller} が付いているか。 */
    private static boolean isControllerClass(JavaClass clazz) {
        return clazz.isAnnotatedWith(RestController.class)
            || clazz.isAnnotatedWith(Controller.class);
    }

    /** メソッドが認可シグナル（A: @PreAuthorize / B: 認可呼び出し）を持つかを検査する条件。 */
    private static ArchCondition<JavaMethod> haveAnAuthorizationSignal() {
        return new ArchCondition<>(
                "have an authorization signal (@PreAuthorize, or a call to "
                    + "AccessControlService/ContentVisibilityChecker/*AccessGuard/*AccessService)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (hasPreAuthorizeSignal(method) || hasAuthorizationCallSignal(method)) {
                    return;
                }
                String message = String.format(
                    "%s is a public Controller Mapping endpoint without an authorization signal "
                        + "(@PreAuthorize / AccessControlService / ContentVisibilityChecker / "
                        + "*AccessGuard / *AccessService call) at %s",
                    method.getFullName(), method.getSourceCodeLocation());
                events.add(SimpleConditionEvent.violated(method, message));
            }
        };
    }

    /** シグナル(A): メソッドまたは宣言クラスに {@code @PreAuthorize} が付いているか。 */
    private static boolean hasPreAuthorizeSignal(JavaMethod method) {
        return method.isAnnotatedWith(PreAuthorize.class)
            || method.getOwner().isAnnotatedWith(PreAuthorize.class);
    }

    /** シグナル(B): メソッド本体が認可呼び出しクラスへ直接メソッド呼び出しをしているか。 */
    private static boolean hasAuthorizationCallSignal(JavaMethod method) {
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            JavaClass targetOwner = call.getTarget().getOwner();
            if (isAuthorizationClass(targetOwner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 呼び出し先クラスが「認可呼び出しクラス」に該当するか。
     *
     * <p>{@code AccessControlService}/{@code ContentVisibilityChecker} は正準クラスを
     * FQN 一致で判定し、{@code *AccessGuard}/{@code *AccessService} は単純クラス名の
     * suffix で判定する（命名規約ベースで新規クラス追加に自動追従させるため）。
     */
    private static boolean isAuthorizationClass(JavaClass clazz) {
        String fullName = clazz.getFullName();
        if (ACCESS_CONTROL_SERVICE_FQN.equals(fullName)
                || CONTENT_VISIBILITY_CHECKER_FQN.equals(fullName)) {
            return true;
        }
        String simpleName = clazz.getSimpleName();
        return simpleName.endsWith(ACCESS_GUARD_SUFFIX) || simpleName.endsWith(ACCESS_SERVICE_SUFFIX);
    }
}
