package com.mannschaft.app.survey.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.mannschaft.app.survey.controller.SurveyController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 試練（#2635）— AC-5 回帰防止。
 *
 * <p>{@link SurveyDetailResponse} をフラット化する改修（御裁可 案2）にあたり、
 * <b>一覧・更新・公開・締切・延長</b> の応答形は従来どおり
 * フラットな {@link SurveyResponse}（{@code data.id}）のままであることを固定する。
 * 改修の巻き添えでこれらの EP の返却型・プロパティ形が変わったら本テストが落ちる。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-5</b>。</p>
 */
@DisplayName("SurveyResponse — 既存応答形の回帰防止（AC-5）")
class SurveyResponseShapeRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-5: 一覧・更新・公開・締切・延長は SurveyResponse を返し続ける")
    void ac5_unchangedEndpointsStillReturnSurveyResponse() {
        assertThat(innermostGenericType("listSurveys"))
                .as("AC-5: 一覧の要素型")
                .isEqualTo(SurveyResponse.class);

        for (String method : List.of("updateSurvey", "publishSurvey", "closeSurvey", "extendDeadline")) {
            assertThat(innermostGenericType(method))
                    .as("AC-5: %s の応答型", method)
                    .isEqualTo(SurveyResponse.class);
        }
    }

    @Test
    @DisplayName("AC-5: SurveyResponse の JSON プロパティ形は不変（id はトップレベル）")
    void ac5_surveyResponsePropertiesUnchanged() {
        BeanDescription description = MAPPER.getSerializationConfig()
                .introspect(MAPPER.constructType(SurveyResponse.class));
        List<String> properties = description.findProperties().stream()
                .map(BeanPropertyDefinition::getName)
                .toList();

        assertThat(properties).containsExactlyInAnyOrder(
                "id", "status", "scope", "content", "policy",
                "distribution", "schedule", "stats", "audit");
    }

    /**
     * {@code ResponseEntity<Wrapper<T>>} から最内の型引数 {@code T} を取り出す。
     */
    private static Class<?> innermostGenericType(String methodName) {
        Method method = java.util.Arrays.stream(SurveyController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("メソッドが見つからない: " + methodName));

        Type type = method.getGenericReturnType();
        while (type instanceof ParameterizedType parameterized) {
            type = parameterized.getActualTypeArguments()[0];
        }
        return (Class<?>) type;
    }
}
