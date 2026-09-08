package com.mannschaft.app.schedule;

import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.schedule.controller.OrgScheduleKeepController;
import com.mannschaft.app.schedule.controller.TeamScheduleKeepController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1920 AC-6: キープ全22入口の正準スコープ型とslug専用解決への退行を見張る。
 * 実HTTP契約はScheduleAuthzScopeContractITが担い、本番入口の走査漏れを本番クラスの列挙で防ぐ。
 * 凍結ストアを読み書きしないため、単独実行してもArchUnitの凍結値を変更しない。
 */
@DisplayName("CMP-260826-1920: キープ全22入口の正準スコープ型")
class ScheduleKeepScopeIdContractTest {

    @ParameterizedTest
    @ValueSource(classes = {TeamScheduleKeepController.class, OrgScheduleKeepController.class})
    @DisplayName("AC-6: 各11入口が必須パス変数に正準スコープ型を使う")
    void 全入口のスコープパス変数は正準型である(Class<?> controller) {
        Class<?> expectedType = controller == TeamScheduleKeepController.class ? TeamScopeId.class : OrgScopeId.class;
        List<Method> endpoints = Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(endpoints).extracting(Method::getName).containsExactlyInAnyOrder(
                "create", "list", "get", "update", "delete", "convert", "reorder",
                "getByConvertedSchedule", "archive", "restore", "revert");
        for (Method endpoint : endpoints) {
            assertThat(endpoint.getParameters()[0].getType())
                    .as("%s#%s のスコープ型", controller.getSimpleName(), endpoint.getName())
                    .isEqualTo(expectedType);
            PathVariable pathVariable = endpoint.getParameters()[0].getAnnotation(PathVariable.class);
            assertThat(pathVariable).as("スコープは必須パス変数").isNotNull();
            assertThat(pathVariable.required()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {TeamScheduleKeepController.class, OrgScheduleKeepController.class})
    @DisplayName("AC-6: Controllerはslug専用解決を直接呼ばない")
    void slug専用解決を直接呼ばない(Class<?> controller) throws IOException {
        Path source = Path.of("src/main/java", controller.getName().replace('.', '/') + ".java");

        assertThat(Files.readString(source)).doesNotContainPattern("\\.\\s*resolve(?:Team|Org)Id\\s*\\(");
    }
}
