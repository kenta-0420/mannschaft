package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.IntentionallyPublic;
import java.lang.annotation.Annotation;
import org.springframework.security.access.prepost.PreAuthorize;

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
 *   <li>(C) メソッド または宣言クラスに<b>監査済マーカー 3 種のいずれか</b>。認可の所在ごとに
 *       分離されており、実態と異なるマーカーを貼ることは誤った証跡として禁じられる:
 *       <ul>
 *         <li>{@link com.mannschaft.app.common.security.AuthorizedInService} —
 *             webhook 署名検証・capability トークン等、白名簿クラスを介さず
 *             Service 内の別方式で認可済み</li>
 *         <li>{@link com.mannschaft.app.common.security.AuthorizedByPathConfig} —
 *             {@code SecurityConfig} のパス単位 {@code hasRole()} 等で宣言的に強制済み</li>
 *         <li>{@link com.mannschaft.app.common.security.IntentionallyPublic} —
 *             {@code permitAll()} 配下で意図的に無認可公開（理由の併記が必須）</li>
 *       </ul></li>
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

    /** 委譲探索の起点パッケージ（外部ライブラリへ潜らないための境界）。 */
    private static final String APP_ROOT_PACKAGE = "com.mannschaft.app";
    /** 委譲探索の深さ上限（起点 Mapping メソッド=深さ0、そこから 2 ホップまで＝案① D=2）。 */
    private static final int MAX_DELEGATION_DEPTH = 2;

    @ArchTest
    static final ArchRule public_controller_endpoints_must_have_authorization_signal =
        FreezingArchRule.freeze(
            methods().that(ControllerEndpoints.areMappingEndpointsOfControllerClasses())
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

    // Controller 走査述語（areMappingEndpointsOfControllerClasses / isControllerClass）は
    // ControllerEntityResponseArchTest（D-6）と共用するため {@link ControllerEndpoints} へ抽出済み。

    /** メソッドが認可シグナル（A: @PreAuthorize / B: 認可呼び出し）を持つかを検査する条件。 */
    private static ArchCondition<JavaMethod> haveAnAuthorizationSignal() {
        return new ArchCondition<>(
                "have an authorization signal (@PreAuthorize, or a call to "
                    + "AccessControlService/ContentVisibilityChecker/*AccessGuard/*AccessService)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (hasAuthorizationSignal(method)) {
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

    /**
     * メソッドが認可シグナル（A: {@code @PreAuthorize} / B: 認可呼び出し）を持つかを返す。
     *
     * <p>本番番人（{@link #haveAnAuthorizationSignal()}）と偽陰性ゼロ証明メタテスト
     * {@code AuthzControllerGuardConditionTest} の双方から呼ばれる合格判定の単一の正準。
     * 判定ロジックを二重実装しないため package-visible の static ヘルパとして公開する。
     */
    static boolean hasAuthorizationSignal(JavaMethod method) {
        return hasPreAuthorizeSignal(method)
            || hasMarkerSignal(method)
            || hasAuthorizationCallSignal(method);
    }

    /** シグナル(A): メソッドまたは宣言クラスに {@code @PreAuthorize} が付いているか。 */
    private static boolean hasPreAuthorizeSignal(JavaMethod method) {
        return method.isAnnotatedWith(PreAuthorize.class)
            || method.getOwner().isAnnotatedWith(PreAuthorize.class);
    }

    /**
     * シグナル(C): メソッドまたは宣言クラスに<b>監査済マーカー 3 種のいずれか</b>が付いているか。
     *
     * <p>番人の呼び出しグラフ判定（{@code @PreAuthorize} / 白名簿クラス呼び出し）では拾えないが、
     * 監査を経て正当と確認されたエンドポイントを明示承認するためのマーカー群。
     * <b>認可の所在ごとに 3 種へ分離</b>している（「Service 内で認可済み」の意の注釈を
     * SecurityConfig のパス認可 EP や意図的公開 EP に貼ると誤った証跡になるため）:</p>
     * <ul>
     *   <li>{@link AuthorizedInService} — webhook 署名検証・capability トークン等、
     *       Service 内の別方式で認可済み</li>
     *   <li>{@link AuthorizedByPathConfig} — {@code SecurityConfig} のパス単位
     *       {@code hasRole()} 等で宣言的に強制済み</li>
     *   <li>{@link IntentionallyPublic} — {@code permitAll()} 配下で意図的に無認可公開</li>
     * </ul>
     *
     * <p>{@code @PreAuthorize} 判定（{@link #hasPreAuthorizeSignal(JavaMethod)}）と完全対称に
     * メソッド／宣言クラスの双方を検査する。</p>
     */
    private static boolean hasMarkerSignal(JavaMethod method) {
        return hasMarker(method, AuthorizedInService.class)
            || hasMarker(method, AuthorizedByPathConfig.class)
            || hasMarker(method, IntentionallyPublic.class);
    }

    /** メソッド または宣言クラスに指定の監査済マーカーが付いているか（メソッド／クラス対称判定）。 */
    private static boolean hasMarker(JavaMethod method, Class<? extends Annotation> marker) {
        return method.isAnnotatedWith(marker)
            || method.getOwner().isAnnotatedWith(marker);
    }

    /**
     * シグナル(B): 起点 Mapping メソッドから <b>呼び出しグラフを深さ {@value #MAX_DELEGATION_DEPTH}
     * まで BFS で辿り</b>、訪問した各メソッドのいずれかが認可呼び出しクラスへ
     * 直接メソッド呼び出しをしていれば認可シグナルありと判定する（Wave5 賢化・案① D=2）。
     *
     * <p><b>賢化の理由</b>: 従来は「起点メソッドが直接 {@code *AccessGuard} 等を呼ぶ」場合のみ
     * 合格とし、認可を Service に委譲した薄い Controller が全て凍結ストアに落ちていた
     * （＝返済対象の負債）。そこで <b>Controller → 注入 Service → private helper → 認可クラス</b>
     * のような 2 ホップまでの委譲を認可シグナルとして認識する。
     *
     * <p><b>探索の必須ガード</b>:
     * <ul>
     *   <li>各訪問メソッドで「直接呼び先が認可クラスか」を判定（到達しない限り合格させない）</li>
     *   <li>{@code visited}（FQN 集合）で同一メソッド再訪を防ぐ＝サイクルガード</li>
     *   <li>深さ上限 {@value #MAX_DELEGATION_DEPTH}（起点=深さ0、そこから 2 ホップまで）</li>
     *   <li>展開対象を {@code com.mannschaft.app} パッケージ配下に限定
     *       （外部ライブラリへ潜って指数爆発しないため）</li>
     * </ul>
     *
     * <p>判定を緩めすぎない: 白名簿クラス（{@code AccessControlService}/
     * {@code ContentVisibilityChecker}/{@code *AccessGuard}/{@code *AccessService}）へ
     * <b>到達しない限り合格させない</b>。この不合格側は
     * {@code AuthzControllerGuardConditionTest} の unauthorized fixture で担保する。
     *
     * <p><b>既知の限界</b>: interface 経由の Service 呼び出しは
     * {@link com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget#resolveMember()}
     * が具象実装体を返さず（interface メソッドに解決 or 解決不能）救済漏れの可能性がある。
     * 本リポは具象 Service 主体のため影響は小さいが、interface 委譲 Controller は
     * 従来どおり凍結ストアに残る（保守的に不合格＝偽陰性を作らない側に倒す）。
     */
    private static boolean hasAuthorizationCallSignal(JavaMethod method) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<MethodAtDepth> queue = new java.util.ArrayDeque<>();
        visited.add(method.getFullName());
        queue.add(new MethodAtDepth(method, 0));

        while (!queue.isEmpty()) {
            MethodAtDepth current = queue.poll();
            JavaMethod visiting = current.method();

            // (1) 訪問メソッドが直接 認可クラスを呼んでいれば合格。
            for (JavaMethodCall call : visiting.getMethodCallsFromSelf()) {
                if (isAuthorizationClass(call.getTarget().getOwner())) {
                    return true;
                }
            }

            // (2) 深さ上限に達していなければ委譲先メソッドを展開する。
            if (current.depth() >= MAX_DELEGATION_DEPTH) {
                continue;
            }
            for (JavaMethodCall call : visiting.getMethodCallsFromSelf()) {
                // 呼び出し先を import 済みの実装メソッドへ解決する。
                // interface 経由等で具象体に解決できない場合 Optional.empty() となり
                // その枝は辿らない（保守的に不合格側へ倒す＝偽陰性を作らない）。
                java.util.Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isEmpty()) {
                    continue;
                }
                JavaMethod callee = resolved.get();
                // 外部ライブラリへは潜らない（アプリ内実装のみ辿る）。
                if (!isWithinAppPackage(callee.getOwner())) {
                    continue;
                }
                // 同一メソッドの再訪を防ぐ（サイクルガード）。
                if (visited.add(callee.getFullName())) {
                    queue.add(new MethodAtDepth(callee, current.depth() + 1));
                }
            }
        }
        return false;
    }

    /** 呼び出しグラフ BFS のノード（メソッドと起点からの深さ）。 */
    private record MethodAtDepth(JavaMethod method, int depth) { }

    /** クラスがアプリ本体パッケージ（{@value #APP_ROOT_PACKAGE}）配下か。 */
    private static boolean isWithinAppPackage(JavaClass clazz) {
        return clazz.getPackageName().startsWith(APP_ROOT_PACKAGE);
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
