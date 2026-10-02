package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.CacheableAuthzFixtureService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「認可を内包した {@code @Cacheable}」番人（{@link CacheableAuthzEnforcementGuardTest}）の
 * 判定ロジックが<b>偽陰性ゼロ</b>であることを証明するメタテスト（issue #2496）。
 *
 * <h2>なぜ必要か</h2>
 * <p>番人本体は発足時点で違反 0 件（クリーン発足）である。しかし
 * <b>「違反 0 件」は「番人が動いている」ことの証明にはならない</b> ——
 * 判定ロジックが常に空リストを返す壊れ方をしていても、本番走査は緑のままだからである。
 * よって既存の {@link AuthzControllerGuardConditionTest} /
 * {@code ControllerEntityResponseConditionTest} と同じ流儀で、
 * {@code architecture/fixtures/} に<b>意図的な違反</b>を置き、
 * 番人の<b>合格判定の単一正準</b>である
 * {@link CacheableAuthzEnforcementGuardTest#findEnforcingAuthzCalls(JavaMethod)}
 * を fixture 限定で評価する（判定ロジックの二重実装を避ける）。</p>
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で
 * test 配下を除外しているため、本メタテストの fixture が本番走査に混入することはない。</p>
 *
 * <h2>担保するケース</h2>
 * <ul>
 *   <li><b>inline</b>: {@code @Cacheable} 本体で直接ゲートを呼ぶ → <b>検出される</b>
 *       （issue #2496 で実在した形そのもの）</li>
 *   <li><b>helper</b>: 同クラス private helper に認可を隠す → <b>検出される</b>
 *       （深さを持たせる前は red になる遷移点）</li>
 *   <li><b>nested</b>: helper → helper と 2 段挟む → <b>検出される</b>（推移探索）</li>
 *   <li><b>lookup-only</b>: 照会系 {@code isAdmin} のみ → <b>検出されない</b>
 *       （{@code RoleResolver} 型の正当形を巻き込まない＝緩めすぎでも厳しすぎでもない担保）</li>
 *   <li><b>clean</b>: 認可呼びなし → <b>検出されない</b></li>
 * </ul>
 */
@DisplayName("@Cacheable内認可 番人 判定ロジックの偽陰性ゼロ証明（メタテスト）")
class CacheableAuthzEnforcementGuardConditionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        // fixture パッケージだけを読み込む（本番番人とは独立の import）。
        // 同パッケージに委譲先（DummyCacheableAccessGuard）も居るため呼び出し辺を解決できる。
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    // ------------------------------------------------------------------
    // 違反が検出されること（＝番人が実際に動いている証明）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inline: @Cacheable 本体で直接ゲートを呼ぶ形は違反として検出される")
    void 直接呼びは違反として検出される() {
        List<String> violations = detect("inlineAuthz");

        assertThat(violations)
            .as("@Cacheable の本体で checkAccess を呼ぶ形は issue #2496 で実在した違反そのもの。"
                + "ここが空になるなら番人は機能していない")
            .isNotEmpty();
        assertThat(violations.getFirst())
            .as("違反メッセージには到達先のゲート名が含まれるべき（是正の手がかり）")
            .contains("checkAccess");
    }

    @Test
    @DisplayName("helper: 同クラス private helper に隠した認可も違反として検出される（深さ）")
    void ヘルパー経由も違反として検出される() {
        assertThat(detect("authzViaHelper"))
            .as("private helper に一段隠しただけで検出できなくなるなら、"
                + "『@Cacheable foo() { assertAccess(); }』というごく自然な形を取り逃す")
            .isNotEmpty();
    }

    @Test
    @DisplayName("nested: helper を2段挟んでも違反として検出される（推移探索）")
    void 多段ヘルパー経由も違反として検出される() {
        assertThat(detect("authzViaNestedHelper"))
            .as("同一クラス内は visited 集合つき BFS で推移的に辿るため、段数を増やしても検出されるべき")
            .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // 正当形を巻き込まないこと（＝緩めすぎでも厳しすぎでもない担保）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lookup-only: 照会系(isAdmin)のみを呼ぶ正当形は違反にならない")
    void 照会系のみは違反にならない() {
        assertThat(detect("lookupOnly"))
            .as("RoleResolver#resolveViewerRole のように『ロールを調べて返す』のは正当形。"
                + "ここを違反にすると番人が厳しすぎて運用できなくなる")
            .isEmpty();
    }

    @Test
    @DisplayName("clean: 認可呼びが無い @Cacheable は違反にならない")
    void 認可呼びなしは違反にならない() {
        assertThat(detect("noAuthz"))
            .as("認可を一切呼ばない素のキャッシュ対象メソッドを違反にしてはならない")
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // 判定要素の単体確認（定義の取りこぼし防止）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("認可クラス判定: *AccessService / *AuthorizationService を取りこぼさない")
    void 認可クラス判定が兄弟番人と揃っている() {
        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("MatchAccessService"))
            .as("*AccessService を落とすと Match / Tournament 系が丸ごと対象外になる").isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("PaymentAuthorizationService"))
            .as("*AuthorizationService も兄弟番人のゲートクラス定義に含まれる").isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("TodoAccessGuard")).isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("AccessControlService")).isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("ContentVisibilityChecker")).isTrue();

        assertThat(CacheableAuthzEnforcementGuardTest.isAuthzClass("TeamFriendRepository"))
            .as("認可と無関係なクラスを認可クラス扱いしてはならない").isFalse();
    }

    @Test
    @DisplayName("ゲートメソッド判定: validate* / verify* / authorize* も例外送出型として扱う")
    void 例外送出型ゲートの接頭辞が実コードを網羅している() {
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("validatePersonalProjectAccess"))
            .as("ProjectAccessGuard の実メソッド").isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("verifyScopeAndMembership"))
            .as("TodoAccessGuard の実メソッド").isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("authorizeBulkPaymentByAdmin"))
            .as("PaymentAuthorizationService の実メソッド").isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("checkMembership")).isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("requireTeamAdmin")).isTrue();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("assertCanView")).isTrue();

        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("isAdmin"))
            .as("照会系は例外送出型ではない").isFalse();
        assertThat(CacheableAuthzEnforcementGuardTest.isEnforcingMethod("getRoleName"))
            .as("照会系は例外送出型ではない").isFalse();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** fixture の指定メソッドに対して番人の判定ロジックを評価する。 */
    private static List<String> detect(String methodName) {
        JavaClass fixture = fixtureClasses.get(CacheableAuthzFixtureService.class);
        JavaMethod method = fixture.getMethods().stream()
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "fixture メソッドが見つからない: " + methodName));
        return CacheableAuthzEnforcementGuardTest.findEnforcingAuthzCalls(method);
    }
}
