package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.fixtures.AuthorizedDirectController;
import com.mannschaft.app.common.architecture.fixtures.HelperDepth2Controller;
import com.mannschaft.app.common.architecture.fixtures.UnauthorizedController;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 認可番人（{@link AuthzControllerGuardArchTest}）の合格判定ロジックが
 * <b>偽陰性ゼロ</b>であることを証明するメタテスト（認可根治戦役 Wave5・Ph0-c）。
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で
 * test 配下を除外しており、本メタテストの fixture は本番凍結ストアに混入しない。
 * 本テストは fixture パッケージだけを {@link ClassFileImporter} で読み込み、
 * 番人の <b>合格判定の単一正準</b> である
 * {@link AuthzControllerGuardArchTest#hasAuthorizationSignal(JavaMethod)}
 * を fixture 限定で評価する（判定ロジックの二重実装を避ける）。
 *
 * <h2>担保する3ケース</h2>
 * <ul>
 *   <li><b>authorized-direct</b>: 直接 {@code *AccessGuard} を呼ぶ → 認可シグナルあり（合格）</li>
 *   <li><b>unauthorized</b>: 認可呼びが一切ない → 認可シグナルなし（違反として検出）
 *       ＝ 番人を緩めすぎていないことの担保</li>
 *   <li><b>helper-depth2</b>: Controller→Service→private helper→{@code *AccessGuard}（深さ2）
 *       → D=2 BFS で認可シグナルあり（合格）。
 *       賢化前（直接呼びのみ判定）はここが検出漏れ＝<b>red</b>、
 *       賢化後（D=2 BFS）は <b>green</b> になる遷移点。</li>
 * </ul>
 */
@DisplayName("認可番人 合格判定ロジックの偽陰性ゼロ証明（メタテスト）")
class AuthzControllerGuardConditionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        // fixture パッケージだけを読み込む（本番番人とは独立の import）。
        // BFS の委譲先（DummyDelegateService/DummyAccessGuard）も同パッケージのため
        // resolveMembers() で実装体に解決できる。
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    @Test
    @DisplayName("authorized-direct: 直接AccessGuardを呼ぶEPは認可シグナルありと判定される")
    void authorizedDirectHasSignal() {
        JavaMethod method = mappingMethod(AuthorizedDirectController.class, "directCall");
        assertThat(AuthzControllerGuardArchTest.hasAuthorizationSignal(method))
            .as("直接 *AccessGuard を呼ぶ公開EPは認可シグナルありと判定されるべき")
            .isTrue();
    }

    @Test
    @DisplayName("unauthorized: 認可呼びが皆無のEPは認可シグナルなし（違反）と判定される")
    void unauthorizedHasNoSignal() {
        JavaMethod method = mappingMethod(UnauthorizedController.class, "noAuth");
        assertThat(AuthzControllerGuardArchTest.hasAuthorizationSignal(method))
            .as("認可呼びが一切ない公開EPは認可シグナルなし＝違反として検出されるべき"
                + "（番人を緩めすぎていないことの担保）")
            .isFalse();
    }

    @Test
    @DisplayName("helper-depth2: 深さ2の委譲で認可クラスに到達するEPは認可シグナルありと判定される（D=2）")
    void helperDepth2HasSignal() {
        JavaMethod method = mappingMethod(HelperDepth2Controller.class, "viaService");
        assertThat(AuthzControllerGuardArchTest.hasAuthorizationSignal(method))
            .as("Controller→Service→private helper→*AccessGuard（深さ2）の委譲は "
                + "D=2 BFS で認可シグナルありと判定されるべき"
                + "（賢化前は直接呼びのみ判定のため検出漏れ＝red）")
            .isTrue();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** fixture Controller から指定名の Mapping メソッド（引数1つ・Long or それ以外の単一引数）を取得する。 */
    private static JavaMethod mappingMethod(Class<?> controller, String methodName) {
        JavaClass javaClass = fixtureClasses.get(controller);
        return javaClass.getMethods().stream()
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "fixture メソッドが見つからない: " + controller.getName() + "#" + methodName));
    }
}
