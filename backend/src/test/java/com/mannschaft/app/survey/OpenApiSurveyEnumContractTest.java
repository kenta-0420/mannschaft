package com.mannschaft.app.survey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 試練（#2617-1,2）— {@code docs/openapi.json} に survey 系 enum の許可値が列挙されることの契約テスト。
 *
 * <p>DTO が {@code String} 型のままだと openapi は {@code {"type":"string","minLength":1}} としか
 * 出力せず、FE の生成型に許可値が伝わらない。結果として FE/BE の enum ドリフト（例:
 * {@code VIEWERS_ONLY} を {@code ADMINS_ONLY} に畳んでしまう変換）を番人が機械的に検出できない。
 * DTO を enum 型にすると springdoc が {@code enum} キーを出力するため、それを固定する。</p>
 *
 * <p>本テストは openapi.json の<b>再生成そのものは行わない</b>（出陣側の責務）。
 * 再生成が漏れたまま実装が進むと red のまま残る。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-14</b>。</p>
 */
@DisplayName("docs/openapi.json — survey 系 enum の許可値列挙（AC-14）")
class OpenApiSurveyEnumContractTest {

    private static JsonNode schemas;

    @BeforeAll
    static void loadOpenApi() throws Exception {
        Path path = locateOpenApiJson();
        assertThat(path)
                .as("docs/openapi.json が見つからない（テストの前提が壊れている）")
                .isNotNull();
        schemas = new ObjectMapper().readTree(Files.readString(path))
                .path("components").path("schemas");
        assertThat(schemas.isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("AC-14: CreateSurveyRequest.resultsVisibility に enum 許可値が出る")
    void ac14_resultsVisibilityHasEnumValues() {
        JsonNode node = schemas.path("CreateSurveyRequest").path("properties").path("resultsVisibility");

        assertThat(node.isMissingNode()).as("resultsVisibility プロパティが存在しない").isFalse();
        assertThat(node.has("enum"))
                .as("AC-14: enum キーが無い（現状は {\"type\":\"string\",\"minLength\":1}）。実値=%s", node)
                .isTrue();
        assertThat(enumValues(node))
                .as("AC-14: ResultsVisibility の全値（ALWAYS を含む）が列挙されること")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(ResultsVisibility.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("AC-14: CreateQuestionRequest.questionType に enum 許可値が出る")
    void ac14_questionTypeHasEnumValues() {
        JsonNode node = schemas.path("CreateQuestionRequest").path("properties").path("questionType");

        assertThat(node.isMissingNode()).as("questionType プロパティが存在しない").isFalse();
        assertThat(node.has("enum"))
                .as("AC-14: enum キーが無い。実値=%s", node)
                .isTrue();
        assertThat(enumValues(node))
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(QuestionType.values()).map(Enum::name).toList());
    }

    /**
     * AC-1〜AC-4 の補助 — openapi 上でも {@code SurveyDetailResponse} がフラット形であること。
     */
    @Test
    @DisplayName("AC-14(補): SurveyDetailResponse は openapi 上でもフラット形（survey キー無し）")
    void ac14_surveyDetailResponseIsFlatInOpenApi() {
        JsonNode properties = schemas.path("SurveyDetailResponse").path("properties");

        assertThat(properties.has("survey"))
                .as("入れ子の survey キーは廃止する（御裁可 案2）")
                .isFalse();
        assertThat(properties.has("id")).as("id はトップレベル").isTrue();
        assertThat(properties.has("questions")).as("questions は保持").isTrue();
    }

    private static List<String> enumValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.path("enum").forEach(v -> values.add(v.asText()));
        return values;
    }

    /** backend モジュールからの相対位置でリポジトリ直下の docs/openapi.json を探す。 */
    private static Path locateOpenApiJson() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("openapi.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
