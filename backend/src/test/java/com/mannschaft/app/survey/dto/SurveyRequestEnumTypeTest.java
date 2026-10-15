package com.mannschaft.app.survey.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 試練（#2617-1,2）— survey 系リクエスト DTO の enum 化契約テスト。
 *
 * <p>現状 {@code CreateSurveyRequest.resultsVisibility} / {@code CreateQuestionRequest.questionType}
 * は {@code String} 型であり、(1) 不正値が DTO 境界を素通りし、(2) {@code docs/openapi.json} に
 * 許可値が出ず（{@code {"type":"string","minLength":1}}）FE との enum ドリフトを番人が検出できない。
 * これを enum 型で受けることで、不正値は Jackson の束縛段階で弾かれ 400 となり、
 * OpenAPI にも許可値が列挙される。</p>
 *
 * <p>HTTP ステータスとしての 400 は {@code SurveyDetailShapeContractIT} が検証する（二段構え）。
 * 本テストは型そのものと束縛の失敗を実行時に固定する。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-12 / AC-13</b>。</p>
 */
@DisplayName("survey リクエスト DTO の enum 化（AC-12 / AC-13）")
class SurveyRequestEnumTypeTest {

    /**
     * Spring Boot 既定の ObjectMapper と同じくコンストラクタ引数名で束縛できるようにする
     * （本 DTO 群は全 final ＋ {@code @RequiredArgsConstructor} のため
     * {@code ParameterNamesModule} が無いとそもそも構築できず、
     * 「不正値を弾いた」のか「作れなかった」のか区別がつかなくなる）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new ParameterNamesModule());

    @Test
    @DisplayName("AC-12: CreateSurveyRequest.resultsVisibility は ResultsVisibility 型")
    void ac12_resultsVisibilityIsEnumTyped() throws Exception {
        assertThat(CreateSurveyRequest.class.getDeclaredField("resultsVisibility").getType())
                .as("AC-12: String ではなく enum で受けること（OpenAPI に許可値を出すため）")
                .isEqualTo(ResultsVisibility.class);
    }

    @Test
    @DisplayName("AC-12: 不正値 \"RESPONDENTS\" は束縛に失敗する（→ 400）")
    void ac12_invalidResultsVisibilityIsRejected() {
        String json = """
                {"title":"試練","isAnonymous":false,"allowMultipleSubmissions":false,
                 "resultsVisibility":"RESPONDENTS","distributionMode":"ALL"}
                """;

        // 値名がメッセージに現れることまで見る（「そもそも構築できない」等の別要因での
        // 偽の緑を避けるため。空の失敗は「弾いた」の証拠にならない）。
        assertThatThrownBy(() -> MAPPER.readValue(json, CreateSurveyRequest.class))
                .as("AC-12: FE 由来の未知値は DTO 境界で弾かれること")
                .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                .hasMessageContaining("RESPONDENTS");
    }

    @Test
    @DisplayName("AC-13: CreateQuestionRequest.questionType は QuestionType 型")
    void ac13_questionTypeIsEnumTyped() throws Exception {
        assertThat(CreateQuestionRequest.class.getDeclaredField("questionType").getType())
                .as("AC-13: String ではなく enum で受けること")
                .isEqualTo(QuestionType.class);
    }

    @Test
    @DisplayName("AC-13: 不正値 \"TEXT\" は束縛に失敗する（→ 400）")
    void ac13_invalidQuestionTypeIsRejected() {
        String json = """
                {"questionType":"TEXT","questionText":"設問","isRequired":false}
                """;

        assertThatThrownBy(() -> MAPPER.readValue(json, CreateQuestionRequest.class))
                .as("AC-13: FREE_TEXT ではない未知値は DTO 境界で弾かれること")
                .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                .hasMessageContaining("TEXT");
    }

    @Test
    @DisplayName("AC-12/AC-13: 正当値は従来どおり束縛できる（陽性対照）")
    void ac12_ac13_validValuesStillBind() throws Exception {
        CreateSurveyRequest survey = MAPPER.readValue("""
                {"title":"試練","isAnonymous":false,"allowMultipleSubmissions":false,
                 "resultsVisibility":"AFTER_RESPONSE","distributionMode":"ALL"}
                """, CreateSurveyRequest.class);
        assertThat(survey.getTitle()).isEqualTo("試練");

        CreateQuestionRequest question = MAPPER.readValue("""
                {"questionType":"FREE_TEXT","questionText":"設問","isRequired":false}
                """, CreateQuestionRequest.class);
        assertThat(question.getQuestionText()).isEqualTo("設問");
    }
}
