package com.mannschaft.app.survey.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 試練（#2635）— {@link SurveyDetailResponse} のフラット化契約テスト。
 *
 * <p>御裁可（案2）: {@code survey} キーを廃し、{@link SurveyResponse} が持つ 9 フィールド
 * （id / status / scope / content / policy / distribution / schedule / stats / audit）を
 * {@link SurveyDetailResponse} の直下に並べ、{@code questions} を加える。
 * 先例: {@code com.mannschaft.app.event.dto.EventDetailResponse}。</p>
 *
 * <p>本テストは DTO の Jackson シリアライズ形（プロパティ名）とフィールド構成のみを検証し、
 * HTTP 応答としての実形は {@code SurveyDetailShapeContractIT} が検証する（二段構え）。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-1 / AC-2 / AC-3 / AC-4 / AC-6</b>。</p>
 */
@DisplayName("SurveyDetailResponse — フラット化契約（#2635）")
class SurveyDetailResponseShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link SurveyResponse} が持つ 9 フィールド（御裁可で列挙された正本）。 */
    private static final List<String> SURVEY_RESPONSE_FIELDS = List.of(
            "id", "status", "scope", "content", "policy",
            "distribution", "schedule", "stats", "audit");

    /**
     * AC-1 / AC-3 / AC-4 — 作成・詳細取得・複製はいずれも本 DTO を返す。
     * その JSON は {@code id} をトップレベルに持ち、入れ子の {@code survey} キーを持たない。
     */
    @Test
    @DisplayName("AC-1/AC-3/AC-4: id はトップレベルにあり survey キーは存在しない")
    void ac1_ac3_ac4_idIsTopLevelAndSurveyKeyIsGone() {
        List<String> properties = serializedPropertyNames();

        assertThat(properties)
                .as("AC-1/AC-3/AC-4: data.survey.id ではなく data.id で返すこと")
                .contains("id")
                .doesNotContain("survey");
    }

    /**
     * AC-2 — 解体しても {@code questions} を失わない。
     */
    @Test
    @DisplayName("AC-2: questions は解体後も保持される")
    void ac2_questionsIsRetained() {
        assertThat(serializedPropertyNames())
                .as("AC-2: 設問一覧は入れ子解体後も応答に含めること")
                .contains("questions");
    }

    /**
     * AC-6 — {@link SurveyDetailResponse} のフィールドが
     * {@link SurveyResponse} の 9 フィールド ＋ {@code questions} と過不足なく一致する（取りこぼし検出）。
     */
    @Test
    @DisplayName("AC-6: フィールドは SurveyResponse の9フィールド＋questions と完全一致")
    void ac6_fieldsMatchSurveyResponsePlusQuestions() {
        // 前提の固定: SurveyResponse 側が 9 フィールドから増減した場合も本テストが気付く。
        assertThat(declaredInstanceFieldNames(SurveyResponse.class))
                .as("AC-6: SurveyResponse の 9 フィールドが正本")
                .containsExactlyInAnyOrderElementsOf(SURVEY_RESPONSE_FIELDS);

        List<String> expected = new java.util.ArrayList<>(SURVEY_RESPONSE_FIELDS);
        expected.add("questions");

        assertThat(declaredInstanceFieldNames(SurveyDetailResponse.class))
                .as("AC-6: SurveyDetailResponse は 9 フィールド + questions をフラットに持つ")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ───────────────────────── ヘルパ ─────────────────────────

    /** インスタンス生成に依存せず Jackson のシリアライズ対象プロパティ名を取り出す。 */
    private static List<String> serializedPropertyNames() {
        BeanDescription description = MAPPER.getSerializationConfig()
                .introspect(MAPPER.constructType(SurveyDetailResponse.class));
        return description.findProperties().stream()
                .map(BeanPropertyDefinition::getName)
                .toList();
    }

    private static List<String> declaredInstanceFieldNames(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
    }
}
