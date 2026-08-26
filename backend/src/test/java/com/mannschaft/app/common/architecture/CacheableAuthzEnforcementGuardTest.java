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
import org.springframework.cache.annotation.Cacheable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 「認可を内包した {@code @Cacheable}」検出番人（issue #2496）。
 *
 * <h2>背景 — 既存の認可番人が揃って見逃す「第三の型」</h2>
 * <p>既存の認可番人は 2 種類ある:</p>
 * <ul>
 *   <li>{@code AuthzControllerGuardArchTest} — 認可を<b>呼んでいるか</b>（呼び出し辺の有無）</li>
 *   <li>{@link AuthzGateReturnValueGuardTest} — 呼んだ結果を<b>捨てていないか</b>（戻り値の消費）</li>
 * </ul>
 * <p>ところが認可ゲートが {@link Cacheable} メソッドの<b>内側</b>にある場合、
 * 呼んでもいるし戻り値も捨てていない。<b>ただキャッシュヒット時に実行されないだけ</b>である。
 * 上記 2 番人はいずれも緑になる。これが issue #2496 で顕在化した第三の型:</p>
 * <pre>{@code
 *   @Cacheable(value = "xxx", key = "#teamId + ...")
 *   public List<Foo> list(Long teamId, Long userId) {
 *       accessControlService.checkMembership(userId, teamId, "TEAM"); // 2回目以降は実行されない
 *       return repository.find(teamId);
 *   }
 * }</pre>
 * <p>1 回目は認可が効くので通常のテストも通る。2 回目以降だけ素通りするため、
 * 単体テスト・契約テストでも極めて捕まえにくい。</p>
 *
 * <h2>本番人が固定する不変条件</h2>
 * <p><b>{@link Cacheable} が付いたメソッドから、例外送出型の認可ゲートに到達してはならない。</b>
 * 認可はキャッシュの<b>外側</b>（キャッシュ対象メソッドを呼ぶ側）で行うこと。</p>
 *
 * <h2>なぜ「キーに行為者が入っていない」との AND 条件にしないのか</h2>
 * <p>issue では「認可を内包し、<b>かつ</b>キーに行為者が入っていない {@code @Cacheable}」の
 * 検出が検討された。本番人はより強い「認可の内包そのものを禁止」を採用する。理由は、
 * キーに {@code userId} が入っていても<b>認可の内包は依然として誤り</b>だからである ——
 * 同一ユーザーが一覧を温めた直後にチームから除名／降格されても、TTL 満了または
 * evict までの窓のあいだ、そのユーザーは自分が温めたエントリにヒットし続け、
 * 所属チェックを一切受けない。AND 条件にすると、この「正しく見える危険な状態」を
 * 番人が承認してしまう。</p>
 *
 * <h2>検出対象</h2>
 * <ol>
 *   <li><b>認可クラス</b>: 単純名が {@code *AccessGuard} / {@code *AccessService} /
 *       {@code *AuthorizationService} で終わる、または {@code AccessControlService} /
 *       {@code ContentVisibilityChecker} であるクラス。
 *       兄弟番人 {@code AuthzGateReturnValueGuardTest#isGateClassFile} のゲートクラス定義に
 *       揃えてある（{@code *AccessService} を落とすと {@code MatchAccessService} /
 *       {@code TournamentContactAccessService} 等が丸ごと対象外になる）。</li>
 *   <li><b>例外送出型ゲートメソッド</b>: 名前が {@code check} / {@code require} /
 *       {@code assert} / {@code validate} / {@code verify} / {@code authorize} で始まるメソッド。
 *       いずれも「拒否時に例外を投げる」様式であり、スキップされた瞬間に認可が消える。
 *       実コードには {@code ProjectAccessGuard#validatePersonalProjectAccess}、
 *       {@code TodoAccessGuard#verifyPersonalAssignee}、
 *       {@code PaymentAuthorizationService#authorizeBulkPaymentByAdmin} 等が存在するため、
 *       {@code check}/{@code require}/{@code assert} の 3 種だけでは recall が足りない。</li>
 * </ol>
 * <p><b>照会系（{@code getRoleName} / {@code isSystemAdmin} / {@code isAdmin} / {@code canX} 等）は対象外。</b>
 * これらは「ロールや可否を調べて<b>返す</b>」関数であり、キャッシュ対象メソッドの戻り値そのものを
 * 組み立てるための正当な入力になり得る（例: {@code RoleResolver#resolveViewerRole} は
 * 閲覧者ロールを解決して返すのが責務であり、キーに {@code userId} を含む正当な実装）。
 * 例外送出型ゲートだけに絞ることで、この正当形を巻き込まない。
 * なお「boolean を返すゲートの戻り値を捨てている」型は兄弟番人
 * {@link AuthzGateReturnValueGuardTest} が担当する。</p>
 *
 * <h2>探索の深さ</h2>
 * <p>{@code @Cacheable} メソッドから、<b>同一クラス内で宣言されたメソッド</b>を辿って
 * 推移的に探索する（visited 集合つき BFS）。これにより
 * {@code @Cacheable foo() { assertAccess(); }}（{@code assertAccess} は同クラスの private helper で
 * 内部的に {@code checkMembership} を呼ぶ）という<b>ごく自然な形</b>を検出できる。</p>
 *
 * <h2>既知の限定（recall より precision を優先）</h2>
 * <ul>
 *   <li><b>他クラス経由の推移呼び出しは追わない</b> —
 *       {@code @Cacheable foo() { otherService.bar(); }} で {@code bar} が内部で認可する形は
 *       検出できない。他 Bean の内部実装まで辿ると正当な委譲まで巻き込む誤検出が急増するため
 *       意図的に打ち切っている（兄弟番人 {@code AuthzControllerGuardArchTest} は
 *       <em>認可の存在を肯定する</em>方向の判定なので D=2 BFS で他クラスも辿るが、
 *       本番人は<em>禁止する</em>方向なので保守的にしてある）。</li>
 *   <li><b>ラムダ式の内部からのゲート呼び出しは検出されない</b> —
 *       バイトコード上 synthetic メソッドへ切り出されるため。</li>
 * </ul>
 *
 * <h2>偽陰性ゼロの証明</h2>
 * <p>「発足時点で違反 0 件」は<b>番人が動いていることの証明にはならない</b>。
 * 本番人の判定ロジック {@link #findEnforcingAuthzCalls(JavaMethod)} は
 * メタテスト {@link CacheableAuthzEnforcementGuardConditionTest} が
 * {@code architecture/fixtures/} の意図的違反 fixture に対して評価し、
 * 「違反を検出できること」「正当形を巻き込まないこと」を担保する。
 * 凍結ストア（{@code FreezingArchRule}）は使わない（{@code --tests} 絞り込み実行で
 * 凍結ストアを破壊する事故を持ち込まないため）。</p>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CacheableAuthzEnforcementGuardTest {

    /** 認可判定を担うクラスの単純名（完全一致）。 */
    private static final Set<String> AUTHZ_CLASS_NAMES = Set.of(
        "AccessControlService",
        "ContentVisibilityChecker"
    );

    /**
     * 認可判定を担うクラスの単純名（接尾辞一致）。
     * 兄弟番人 {@code AuthzGateReturnValueGuardTest#isGateClassFile} と同一の定義。
     */
    private static final Set<String> AUTHZ_CLASS_SUFFIXES = Set.of(
        "AccessGuard",
        "AccessService",
        "AuthorizationService"
    );

    /** 例外送出型ゲートメソッドの名前接頭辞（拒否時に throw する様式）。 */
    private static final Set<String> ENFORCING_METHOD_PREFIXES = Set.of(
        "check",
        "require",
        "assert",
        "validate",
        "verify",
        "authorize"
    );

    @ArchTest
    static final ArchRule cacheable_methods_should_not_enforce_authorization =
        methods().that().areAnnotatedWith(Cacheable.class)
            .should(notReachEnforcingAuthzGate())
            .because("@Cacheable メソッドの内側に置いた認可はキャッシュヒット時に実行されない。"
                + "認可はキャッシュの外側（キャッシュ対象メソッドの呼び出し側）で行うこと（issue #2496）")
            .as("@Cacheable methods should not enforce authorization inside the cached body");

    // ------------------------------------------------------------------
    // 判定ロジック（メタテストと共有する単一正準）
    // ------------------------------------------------------------------

    /**
     * {@code @Cacheable} メソッドから到達する例外送出型認可ゲートの呼び出しを列挙する。
     *
     * <p>同一クラス内で宣言されたメソッドは推移的に辿る（private helper 経由の隠蔽を検出するため）。
     * 他クラスへの呼び出しは辿らない（上位 Javadoc「既知の限定」参照）。</p>
     *
     * <p>本メソッドは番人本体とメタテスト
     * {@link CacheableAuthzEnforcementGuardConditionTest} の<b>両方から呼ばれる単一正準</b>である
     * （判定ロジックの二重実装を避け、メタテストが実際の判定を検証していることを保証する）。</p>
     *
     * @param method 検査対象（{@code @Cacheable} が付いたメソッド）
     * @return 違反の説明文リスト。空なら合格
     */
    static List<String> findEnforcingAuthzCalls(JavaMethod method) {
        List<String> violations = new ArrayList<>();
        JavaClass owner = method.getOwner();

        Deque<JavaMethod> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(method);
        visited.add(method.getFullName());

        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();

            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                JavaClass targetOwner = call.getTarget().getOwner();
                String ownerName = targetOwner.getSimpleName();
                String targetName = call.getTarget().getName();

                if (isAuthzClass(ownerName) && isEnforcingMethod(targetName)) {
                    violations.add(String.format(
                        "@Cacheable メソッド %s.%s() から認可ゲート %s.%s() に到達する"
                            + "（呼び出し元: %s.%s()）。キャッシュヒット時にこの認可は実行されないため、"
                            + "キャッシュの外へ移動すること。(%s)",
                        owner.getSimpleName(), method.getName(),
                        ownerName, targetName,
                        current.getOwner().getSimpleName(), current.getName(),
                        call.getSourceCodeLocation()));
                    continue;
                }

                // 同一クラス内のヘルパーは推移的に辿る（overload はまとめて辿る＝探索側は安全側に倒す）
                if (targetOwner.equals(owner)) {
                    owner.getMethods().stream()
                        .filter(candidate -> candidate.getName().equals(targetName))
                        .filter(candidate -> visited.add(candidate.getFullName()))
                        .forEach(queue::add);
                }
            }
        }

        return violations;
    }

    static boolean isAuthzClass(String simpleName) {
        if (AUTHZ_CLASS_NAMES.contains(simpleName)) {
            return true;
        }
        return AUTHZ_CLASS_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    static boolean isEnforcingMethod(String methodName) {
        return ENFORCING_METHOD_PREFIXES.stream().anyMatch(methodName::startsWith);
    }

    // ------------------------------------------------------------------
    // ArchUnit 条件
    // ------------------------------------------------------------------

    private static ArchCondition<JavaMethod> notReachEnforcingAuthzGate() {
        return new ArchCondition<>("not reach an exception-throwing authorization gate") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (String violation : findEnforcingAuthzCalls(method)) {
                    events.add(SimpleConditionEvent.violated(method, violation));
                }
            }
        };
    }
}
