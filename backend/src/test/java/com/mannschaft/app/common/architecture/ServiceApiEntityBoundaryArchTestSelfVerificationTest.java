package com.mannschaft.app.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.fixtures.D1ServiceApiChildService;
import com.mannschaft.app.common.architecture.fixtures.D1ServiceApiInterface;
import com.mannschaft.app.common.architecture.fixtures.D1ServiceApiParent;
import com.mannschaft.app.common.architecture.fixtures.DummyD6ExposedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/**
 * {@link ServiceApiEntityBoundaryArchTest} が<b>継承経由の Entity 露出を実際に検出する</b>ことを
 * 証明するメタテスト（Codex 検分 P1 是正の裏付け）。
 *
 * <p>初版（{@code methods().that().areDeclaredInClassesThat().areAnnotatedWith(Service.class)}）は
 * メソッドの<b>宣言クラス</b>だけを見ていたため、{@code @Service} クラスが「{@code @Service} の付いて
 * いない親クラス」や「default インターフェースメソッド」から継承した public メソッドで Entity を
 * 公開していても、宣言元が {@code @Service} でないという理由だけで判定対象から漏れていた。
 * 是正後は {@code classes().that().areAnnotatedWith(Service.class)} を起点に
 * {@code JavaClass#getAllMethods()}（継承分を含む）を検査する形にした。
 *
 * <p>本テストは「直したつもり」で終わらせないため、実際に継承構造を持つ fixture
 * （{@link D1ServiceApiParent} → {@link D1ServiceApiChildService} ← {@link D1ServiceApiInterface}）
 * を用意し、番人本体が使う {@link ServiceApiEntityBoundaryArchTest#notExposeEntitiesInReachableApi()}
 * をそのまま {@code fixtures} パッケージだけに限定した非凍結の {@link ArchRule} として評価する
 * （{@code ControllerEntityResponseConditionTest} と同型：判定ロジックの二重実装を避けるため、
 * 番人本体の条件をそのまま呼ぶ。凍結ストアには一切触れない）。
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test 配下を
 * 除外しており、本メタテストの fixture クラスは本番の D-1 API 境界解析へ混入しない。
 */
@DisplayName("D-1 API境界番人の継承経由Entity露出検出の自己検証（Codex検分P1是正）")
class ServiceApiEntityBoundaryArchTestSelfVerificationTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    @Test
    @DisplayName("親クラス由来の継承メソッドがEntity露出として検出される（是正前は漏れていた経路）")
    void parentClassInheritedMethodDetected() {
        List<String> details = evaluateChildServiceOnly();

        assertThat(details)
            .as("D1ServiceApiParent#findEntityFromParent は @Service の付いていない親クラスの宣言だが、"
                + "@Service サブクラス D1ServiceApiChildService から到達可能な public メソッドとして"
                + "検出されるべき")
            .anySatisfy(detail -> assertThat(detail)
                .contains(D1ServiceApiParent.class.getName() + ".findEntityFromParent")
                .contains(DummyD6ExposedEntity.class.getName()));
    }

    @Test
    @DisplayName("defaultインターフェースメソッド由来の継承メソッドがEntity露出として検出される")
    void defaultInterfaceMethodDetected() {
        List<String> details = evaluateChildServiceOnly();

        assertThat(details)
            .as("D1ServiceApiInterface#findEntityFromInterface は default メソッドの宣言だが、"
                + "@Service サブクラス D1ServiceApiChildService から到達可能な public メソッドとして"
                + "検出されるべき")
            .anySatisfy(detail -> assertThat(detail)
                .contains(D1ServiceApiInterface.class.getName() + ".findEntityFromInterface")
                .contains(DummyD6ExposedEntity.class.getName()));
    }

    @Test
    @DisplayName("Entityを公開しない固有メソッドは検出されない（偽陽性ゼロ）")
    void safeMethodNotDetected() {
        List<String> details = evaluateChildServiceOnly();

        assertThat(details)
            .as("safeMethod() は Entity を公開しないため違反として現れてはならない")
            .noneMatch(detail -> detail.contains(".safeMethod"));
    }

    @Test
    @DisplayName("Object由来のequals/hashCode/toStringは誤検出されない")
    void objectDeclaredMethodsNotDetected() {
        List<String> details = evaluateChildServiceOnly();

        assertThat(details)
            .as("java.lang.Object 由来のメソッドは検査対象外であるべき（誤検出ゼロ）")
            .noneMatch(detail -> detail.contains(".equals(")
                || detail.contains(".hashCode(")
                || detail.contains(".toString("));
    }

    @Test
    @DisplayName("同一の継承メソッドは重複して報告されない（凍結ストアの無駄な膨張を防ぐ）")
    void inheritedMethodReportedOnlyOnce() {
        List<String> details = evaluateChildServiceOnly();

        long parentMethodOccurrences = details.stream()
            .filter(d -> d.contains(D1ServiceApiParent.class.getName() + ".findEntityFromParent"))
            .count();

        assertThat(parentMethodOccurrences)
            .as("親クラス由来メソッドの違反は1件のみ報告されるべき")
            .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /**
     * 番人本体が使う {@link ServiceApiEntityBoundaryArchTest#notExposeEntitiesInReachableApi()} を
     * {@code fixtures} パッケージ内の {@code @Service} クラスだけに限定した非凍結ルールとして評価し、
     * 違反の詳細メッセージ一覧を返す（{@link D1ServiceApiChildService} 1 クラストのみが対象）。
     */
    private static List<String> evaluateChildServiceOnly() {
        ArchRule rule = classes().that().areAnnotatedWith(Service.class)
            .should(ServiceApiEntityBoundaryArchTest.notExposeEntitiesInReachableApi());
        EvaluationResult result = rule.evaluate(fixtureClasses);
        return result.getFailureReport().getDetails();
    }
}
