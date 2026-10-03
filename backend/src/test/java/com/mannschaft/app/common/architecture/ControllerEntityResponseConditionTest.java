package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.fixtures.D6ApiResponseEntityController;
import com.mannschaft.app.common.architecture.fixtures.D6DtoController;
import com.mannschaft.app.common.architecture.fixtures.D6NestedEntityController;
import com.mannschaft.app.common.architecture.fixtures.D6PageEntityController;
import com.mannschaft.app.common.architecture.fixtures.D6RawEntityController;
import com.mannschaft.app.common.architecture.fixtures.DummyD6ExposedEntity;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-6 番人（{@link ControllerEntityResponseArchTest}）の Entity 検出ロジックが
 * <b>偽陰性ゼロ</b>（4 様式の Entity 露出を全て検出）かつ<b>偽陽性ゼロ</b>（DTO を誤検出しない）
 * であることを証明するメタテスト。
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test 配下を
 * 除外しており、本メタテストの fixture Controller/Entity は本番の D-6 解析へ混入しない。
 * 本テストは fixture パッケージだけを {@link ClassFileImporter} で読み込み、番人の
 * <b>合格判定の単一正準</b> である
 * {@link ControllerEntityResponseArchTest#exposedEntityReturnTypes(JavaMethod)} を
 * fixture 限定で評価する（判定ロジックの二重実装を避ける）。
 *
 * <h2>担保するケース（4 様式検出 + DTO 非誤検出）</h2>
 * <ul>
 *   <li><b>raw</b>: 素の {@code Entity} 返し → 検出（違反）</li>
 *   <li><b>api-response-entity</b>: {@code ApiResponse<Entity>} → 検出（違反）</li>
 *   <li><b>page-entity</b>: {@code Page<Entity>} → 検出（違反）</li>
 *   <li><b>nested</b>: {@code ResponseEntity<ApiResponse<Page<Entity>>>}（3 段入れ子）→ 検出（違反）</li>
 *   <li><b>dto</b>: {@code DummyD6ResponseDto}（素・3 段入れ子とも）→ 非検出（合格）。
 *       ラッパーの深さではなく {@code @Entity} の有無で判定していることの担保。</li>
 * </ul>
 */
@DisplayName("D-6 番人 Entity検出ロジックの偽陰性ゼロ・偽陽性ゼロ証明（メタテスト）")
class ControllerEntityResponseConditionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    @Test
    @DisplayName("raw: 素のEntity返しは戻り型Entity露出として検出される")
    void rawEntityDetected() {
        assertThat(exposed(D6RawEntityController.class, "raw"))
            .as("素の Entity 返しは D-6 で検出されるべき")
            .contains(DummyD6ExposedEntity.class.getName());
    }

    @Test
    @DisplayName("api-response-entity: ApiResponse<Entity>のジェネリクス引数Entityが検出される")
    void apiResponseEntityDetected() {
        assertThat(exposed(D6ApiResponseEntityController.class, "wrapped"))
            .as("ApiResponse<Entity> の型引数 Entity は D-6 で検出されるべき")
            .contains(DummyD6ExposedEntity.class.getName());
    }

    @Test
    @DisplayName("page-entity: Page<Entity>のジェネリクス引数Entityが検出される")
    void pageEntityDetected() {
        assertThat(exposed(D6PageEntityController.class, "paged"))
            .as("Page<Entity> の型引数 Entity は D-6 で検出されるべき")
            .contains(DummyD6ExposedEntity.class.getName());
    }

    @Test
    @DisplayName("nested: ResponseEntity<ApiResponse<Page<Entity>>>の最深Entityが検出される")
    void nestedEntityDetected() {
        assertThat(exposed(D6NestedEntityController.class, "deeplyNested"))
            .as("3 段入れ子ラップの最深 Entity は D-6 で検出されるべき")
            .contains(DummyD6ExposedEntity.class.getName());
    }

    @Test
    @DisplayName("dto(plain): 素のDTO返しは検出されない（偽陽性ゼロ）")
    void plainDtoNotDetected() {
        assertThat(exposed(D6DtoController.class, "plain"))
            .as("Entity を露出しない素の DTO 返しは D-6 で検出されてはならない")
            .isEmpty();
    }

    @Test
    @DisplayName("dto(nested): 3段入れ子でも最深がDTOなら検出されない（深さでなく@Entity有無で判定）")
    void nestedDtoNotDetected() {
        assertThat(exposed(D6DtoController.class, "nestedDto"))
            .as("同じ 3 段入れ子でも最深が DTO なら D-6 で検出されてはならない"
                + "（ラッパー深さではなく @Entity の有無で判定していることの担保）")
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** fixture Controller の指定メソッドが戻り型に露出する Entity のクラス名一覧を返す。 */
    private static List<String> exposed(Class<?> controller, String methodName) {
        JavaMethod method = mappingMethod(controller, methodName);
        return ControllerEntityResponseArchTest.exposedEntityReturnTypes(method).stream()
            .map(JavaClass::getName)
            .toList();
    }

    /** fixture Controller から指定名のメソッドを取得する。 */
    private static JavaMethod mappingMethod(Class<?> controller, String methodName) {
        JavaClass javaClass = fixtureClasses.get(controller);
        return javaClass.getMethods().stream()
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "fixture メソッドが見つからない: " + controller.getName() + "#" + methodName));
    }
}
