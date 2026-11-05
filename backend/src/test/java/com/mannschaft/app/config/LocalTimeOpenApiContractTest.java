package com.mannschaft.app.config;

import com.mannschaft.app.reservation.controller.ReservationBusinessHourController;
import com.mannschaft.app.reservation.controller.ReservationRecurringBlockedTimeController;
import com.mannschaft.app.reservation.controller.TeamEmergencyClosureController;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 既存の予約プレビュー API の時刻クエリ契約を守る。 */
class LocalTimeOpenApiContractTest {
    private static Stream<Method> endpoints() {
        return Stream.of(ReservationBusinessHourController.class, ReservationRecurringBlockedTimeController.class,
                        TeamEmergencyClosureController.class)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> List.of("getBlockedTimeImpact", "getImpact", "previewClosure").contains(m.getName()));
    }

    @Test
    void 既存3入口の6時刻クエリはdateTime形式() throws Exception {
        List<Method> methods = endpoints().toList();
        assertThat(methods).hasSize(3);
        int count = 0;
        for (Method method : methods) {
            for (int i = 0; i < method.getParameterCount(); i++) {
                MethodParameter argument = new MethodParameter(method, i);
                if (argument.getParameterType() != LocalTime.class
                        || !argument.hasParameterAnnotation(RequestParam.class)) continue;
                count++;
                Parameter result = customize(new Parameter().in("query").schema(new StringSchema()), argument);
                assertThat(result.getSchema().getType()).isEqualTo("string");
                assertThat(result.getSchema().getFormat()).as("%s 引数 %s", method.getName(), i).isEqualTo("date-time");
            }
        }
        assertThat(count).isEqualTo(6);
    }

    @ParameterizedTest
    @MethodSource("unrelatedArguments")
    void 対象外はスキーマを変更しない(int index, String location) throws Exception {
        StringSchema schema = new StringSchema();
        schema.setFormat("uuid");
        Parameter parameter = new Parameter().in(location).schema(schema);
        Parameter result = customize(parameter, new MethodParameter(fixture(), index));
        assertThat(result.getSchema()).isSameAs(schema);
        assertThat(result.getSchema().getFormat()).isEqualTo("uuid");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> unrelatedArguments() {
        return Stream.of(org.junit.jupiter.params.provider.Arguments.of(1, "query"),
                org.junit.jupiter.params.provider.Arguments.of(2, "path"),
                org.junit.jupiter.params.provider.Arguments.of(3, "query"),
                org.junit.jupiter.params.provider.Arguments.of(4, "query"),
                org.junit.jupiter.params.provider.Arguments.of(0, "path"));
    }

    @Test
    void nullモデルと既存formatを維持する() throws Exception {
        MethodParameter argument = new MethodParameter(fixture(), 0);
        assertThat(customize(null, argument)).isNull();
        StringSchema schema = new StringSchema();
        schema.setFormat("custom");
        Parameter parameter = new Parameter().in("query").schema(schema);
        assertThat(customize(parameter, argument).getSchema().getFormat()).isEqualTo("custom");
    }

    private static Parameter customize(Parameter parameter, MethodParameter argument) throws Exception {
        OpenApiConfig config = new OpenApiConfig();
        for (Method bean : OpenApiConfig.class.getDeclaredMethods()) {
            if (bean.isAnnotationPresent(Bean.class) && bean.getReturnType() == ParameterCustomizer.class) {
                parameter = ((ParameterCustomizer) bean.invoke(config)).customize(parameter, argument);
            }
        }
        return parameter;
    }

    private static Method fixture() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("parameters", LocalTime.class, String.class, LocalTime.class,
                LocalTime.class, LocalTime.class);
    }

    private static class Fixture {
        void parameters(@RequestParam LocalTime query, @RequestParam String text, @PathVariable LocalTime path,
                @RequestBody LocalTime body, LocalTime unannotated) { }
    }
}
