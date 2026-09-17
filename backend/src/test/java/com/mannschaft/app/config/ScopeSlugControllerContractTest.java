package com.mannschaft.app.config;

import com.mannschaft.app.analytics.controller.OrganizationAnalyticsController;
import com.mannschaft.app.analytics.controller.TeamAnalyticsController;
import com.mannschaft.app.team.controller.OrganizationTeamSearchController;
import com.mannschaft.app.team.controller.TeamShiftSettingsController;
import com.mannschaft.app.template.controller.OrganizationModuleController;
import com.mannschaft.app.template.controller.TeamModuleController;
import com.mannschaft.app.todo.controller.OrgProjectController;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

/** CMP-112: 対象25入口のslug／数値ID両対応契約を横断的に見張る。 */
@DisplayName("CMP-112: Controllerの正準スコープ型")
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

    private static List<Method> endpoints(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }
}
