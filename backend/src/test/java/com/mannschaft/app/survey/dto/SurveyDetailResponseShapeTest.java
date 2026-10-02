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
     * 詳細応答だけが持つ<b>閲覧者視点</b>の項目（#2779）。
     *
     * <p>アンケートそのものの属性ではなく「この応答を受け取る利用者から見てどうか」を表す。
     * 一覧に載せない理由は {@link #ac6_fieldsMatchSurveyResponsePlusQuestions()} の javadoc を参照。
     * 追加する場合はここに明示的に列挙すること（無断追加は AC-6 が落とす）。</p>
     */
    private static final List<String> VIEWER_SCOPED_FIELDS = List.of(
            "viewerCanViewResults",
            // CMP-041: 管理操作可否・チーム別内訳可否。FE がロール名で操作ボタンを出し分けると
            // 権限を持たない DEPUTY_ADMIN に「押すと 403 になるボタン」が見えるため、
            // viewerCanViewResults と同じ作法で BE の判定点から載せる。
            "viewerCanManage",
            "viewerCanViewTeamBreakdown");

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
     * {@link SurveyResponse} の 9 フィールド ＋ {@code questions} ＋ 閲覧者視点の項目と
     * 過不足なく一致する（取りこぼし・無断追加の検出）。
     *
     * <p><b>なぜ詳細だけが {@code viewerCanViewResults} を持つのか（#2779）</b>:
     * これは「この閲覧者が結果を閲覧できるか」という<b>閲覧者視点</b>の項目であり、
     * アンケートそのものの属性ではない。詳細画面はこの 1 項目のために結果取得 API を
     * 1 回余分に叩き 403 かどうかで判定していた（403 プローブ）ので、詳細応答に載せて往復を無くした。</p>
     *
     * <p>一覧（{@link SurveyResponse}）には<b>あえて載せない</b>。載せると全行分の
     * 配信母集団照会（可視性判定）が必要になり、一覧のコストが行数に比例して膨らむためである。
     * したがって詳細と一覧のフィールド集合が 1 項目だけ非対称になるのは<b>意図した設計</b>であり、
     * その非対称をここで明示的に列挙して固定する。</p>
     *
     * <p>#2635 で敷いた「フィールド集合の完全一致」という検査自体は緩めない。
     * 以後、誰かが無断でフィールドを足せば本テストが落ちる。</p>
     */
    @Test
    @DisplayName("AC-6: フィールドは SurveyResponse の9フィールド＋questions＋viewerCanViewResults と完全一致")
    void ac6_fieldsMatchSurveyResponsePlusQuestions() {
        // 前提の固定: SurveyResponse 側が 9 フィールドから増減した場合も本テストが気付く。
        assertThat(declaredInstanceFieldNames(SurveyResponse.class))
                .as("AC-6: SurveyResponse の 9 フィールドが正本（一覧に閲覧者視点の項目を足さないこと）")
                .containsExactlyInAnyOrderElementsOf(SURVEY_RESPONSE_FIELDS);

        List<String> expected = new java.util.ArrayList<>(SURVEY_RESPONSE_FIELDS);
        expected.add("questions");
        expected.addAll(VIEWER_SCOPED_FIELDS);

        assertThat(declaredInstanceFieldNames(SurveyDetailResponse.class))
                .as("AC-6: SurveyDetailResponse は 9 フィールド + questions + 閲覧者視点の項目をフラットに持つ")
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
