package com.mannschaft.app.config;

import com.mannschaft.app.analytics.controller.OrganizationAnalyticsController;
import com.mannschaft.app.analytics.controller.TeamAnalyticsController;
import com.mannschaft.app.match.controller.MatchRecordController;
import com.mannschaft.app.match.controller.MatchStatsController;
import com.mannschaft.app.team.controller.OrganizationTeamSearchController;
import com.mannschaft.app.team.controller.TeamShiftSettingsController;
import com.mannschaft.app.template.controller.OrganizationModuleController;
import com.mannschaft.app.template.controller.TeamModuleController;
import com.mannschaft.app.todo.controller.OrgProjectController;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateController;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** CMP-112の25入口とCMP-260826-1921の21入口のslug／数値ID契約を見張る。 */
@DisplayName("Controllerの正準スコープ型とOpenAPI契約")
class ScopeSlugControllerContractTest {

    private record ControllerCase(
            Class<?> controller, Class<?> scopeType, String pathVariableName, int endpointCount) {
        @Override
        public String toString() {
            return controller.getSimpleName();
        }
    }

    private static Stream<ControllerCase> controllers() {
        return Stream.of(
                new ControllerCase(OrganizationAnalyticsController.class, OrgScopeId.class, "slug", 1),
                new ControllerCase(TeamAnalyticsController.class, TeamScopeId.class, "slug", 1),
                new ControllerCase(
                        OrganizationTeamSearchController.class, OrgScopeId.class, "orgPublicId", 1),
                new ControllerCase(TeamShiftSettingsController.class, TeamScopeId.class, "slug", 2),
                new ControllerCase(OrganizationModuleController.class, OrgScopeId.class, "slug", 3),
                new ControllerCase(TeamModuleController.class, TeamScopeId.class, "slug", 4),
                new ControllerCase(OrgProjectController.class, OrgScopeId.class, "slug", 13));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controllers")
    @DisplayName("全25入口が必須パス変数に正準スコープ型を使う")
    void 全入口のスコープパス変数は正準型である(ControllerCase target) {
        List<Method> endpoints = endpoints(target.controller());

        assertThat(endpoints).hasSize(target.endpointCount());
        for (Method endpoint : endpoints) {
            assertThat(endpoint.getParameters()).isNotEmpty();
            assertThat(endpoint.getParameters()[0].getType())
                    .as("%s#%s のスコープ型", target.controller().getSimpleName(), endpoint.getName())
                    .isEqualTo(target.scopeType());
            PathVariable pathVariable = endpoint.getParameters()[0].getAnnotation(PathVariable.class);
            assertThat(pathVariable).as("スコープは必須パス変数").isNotNull();
            assertThat(pathVariable.required()).isTrue();
            String actualName = pathVariable.value().isBlank()
                    ? endpoint.getParameters()[0].getName()
                    : pathVariable.value();
            assertThat(actualName)
                    .as("%s#%s のパス変数名", target.controller().getSimpleName(), endpoint.getName())
                    .isEqualTo(target.pathVariableName());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controllers")
    @DisplayName("Controllerはslug専用解決を直接呼ばない")
    void slug専用解決を直接呼ばない(ControllerCase target) throws IOException {
        Path source = Path.of("src/main/java", target.controller().getName().replace('.', '/') + ".java");

        assertThat(Files.readString(source))
                .doesNotContainPattern("\\.\\s*resolve(?:Team|Org)Id\\s*\\(");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controllers")
    @DisplayName("OpenAPIはslugを表せるstring契約を維持する")
    void openApiのスコープ契約を狭めない(ControllerCase target) {
        Method endpoint = endpoints(target.controller()).getFirst();
        Parameter parameter = new Parameter().schema(new StringSchema());

        Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                .customize(parameter, new MethodParameter(endpoint, 0));

        assertThat(customized.getSchema()).isSameAs(parameter.getSchema());
        assertThat(customized.getSchema().getType()).isEqualTo("string");
    }

    private record MatchControllerCase(Class<?> controller, int endpointCount, int scopeParameterCount) {
        @Override
        public String toString() {
            return controller.getSimpleName();
        }
    }

    private static Stream<MatchControllerCase> matchControllers() {
        return Stream.of(
                new MatchControllerCase(MatchRecordController.class, 10, 20),
                new MatchControllerCase(MatchStatsController.class, 5, 8),
                new MatchControllerCase(TournamentEntryTemplateController.class, 6, 10));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchControllers")
    @DisplayName("CMP-260826-1921: 全21入口の38スコープ変数はstringで数値専用変数は維持する")
    void 試合とテンプレートの全入口でスコープ契約を狭めない(MatchControllerCase target) {
        List<Method> methods = endpoints(target.controller());
        assertThat(methods).hasSize(target.endpointCount());
        int scopeParameterCount = 0;

        for (Method endpoint : methods) {
            for (int index = 0; index < endpoint.getParameterCount(); index++) {
                MethodParameter methodParameter = new MethodParameter(endpoint, index);
                if (!methodParameter.hasParameterAnnotation(PathVariable.class)) {
                    continue;
                }
                Class<?> type = methodParameter.getParameterType();
                if (type == OrgScopeId.class || type == TeamScopeId.class) {
                    scopeParameterCount++;
                    StringSchema schema = new StringSchema();
                    Parameter parameter = new Parameter().schema(schema);

                    Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                            .customize(parameter, methodParameter);

                    assertThat(customized).isSameAs(parameter);
                    assertThat(customized.getSchema())
                            .as("%s#%s 引数%d", target.controller().getSimpleName(), endpoint.getName(), index)
                            .isSameAs(schema);
                    assertThat(customized.getSchema().getType()).isEqualTo("string");
                    assertThat(customized.getSchema().getFormat()).isNull();
                } else if (type == Long.class) {
                    IntegerSchema schema = new IntegerSchema().format("int64");
                    Parameter parameter = new Parameter().schema(schema);

                    Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                            .customize(parameter, methodParameter);

                    assertThat(customized.getSchema()).isSameAs(schema);
                    assertThat(customized.getSchema().getType()).isEqualTo("integer");
                    assertThat(customized.getSchema().getFormat()).isEqualTo("int64");
                }
            }
        }
        assertThat(scopeParameterCount).isEqualTo(target.scopeParameterCount());
    }

    @Test
    void nullのパラメータモデルはnullのまま返す() throws NoSuchMethodException {
        MethodParameter methodParameter = new MethodParameter(scopeFixture(), 0);

        Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                .customize(null, methodParameter);

        assertThat(customized).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void 正準スコープ以外の型は既存スキーマを変更しない(int index) throws NoSuchMethodException {
        StringSchema schema = new StringSchema();
        Parameter parameter = new Parameter().schema(schema);

        Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                .customize(parameter, new MethodParameter(scopeFixture(), index));

        assertThat(customized).isSameAs(parameter);
        assertThat(customized.getSchema()).isSameAs(schema);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void slug互換対象外の正準スコープは整数契約を維持する(int index) throws NoSuchMethodException {
        Parameter parameter = new Parameter().schema(new StringSchema());

        Parameter customized = new OpenApiConfig().scopeIdParameterCustomizer()
                .customize(parameter, new MethodParameter(scopeFixture(), index));

        assertThat(customized).isSameAs(parameter);
        assertThat(customized.getSchema().getType()).isEqualTo("integer");
        assertThat(customized.getSchema().getFormat()).isEqualTo("int64");
    }

    private static Method scopeFixture() throws NoSuchMethodException {
        return ScopeParameterFixture.class.getDeclaredMethod(
                "parameters", OrgScopeId.class, TeamScopeId.class, Long.class, String.class);
    }

    /** Customizerの型境界を実パラメータの反射情報で検証する検体。 */
    private static class ScopeParameterFixture {
        public void parameters(OrgScopeId orgId, TeamScopeId teamId, Long numericId, String slug) {
        }
    }

    private static List<Method> endpoints(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }
}
