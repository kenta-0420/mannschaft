package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReflectionMaskedCueExtractor} 単体テスト（F06.5 Phase 4・§13-C-1 / §13-C-2 M-4 / AC-51）。
 *
 * <p><b>セキュリティ核心</b>: cue/answer 取り違えで答えが {@code promptText} に乗らないことを、
 * 両 direction（MEANING_TO_TERM / TERM_TO_MEANING）× 旧形（type 欠落=OUTLINE）/ cards 欠落 /
 * 空 meaning / 空 term を網羅して検証する（例外を投げず静かに漏洩する経路の番人）。</p>
 */
@DisplayName("ReflectionMaskedCueExtractor 単体テスト（§13-C-1 cue 抽出・漏洩番人）")
class ReflectionMaskedCueExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReflectionMaskedCueExtractor extractor = new ReflectionMaskedCueExtractor();

    private JsonNode contentWithTermCard(String term, String meaning) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "英単語");
        ArrayNode sections = root.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        card.put("heading", "今日の単語");
        ArrayNode cards = card.putArray("cards");
        ObjectNode c = cards.addObject();
        if (term != null) {
            c.put("term", term);
        }
        if (meaning != null) {
            c.put("meaning", meaning);
        }
        return root;
    }

    @Test
    @DisplayName("AC-51: MEANING_TO_TERM では cue=meaning のみ。term（答え）が一切載らない")
    void meaningToTerm_cueIsMeaningOnly() {
        JsonNode content = contentWithTermCard("abandon", "見捨てる");

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(content, RecallDirection.MEANING_TO_TERM);

        assertThat(quiz).hasSize(1);
        ReflectionEntryResponse.MaskedCardQuiz q = quiz.get(0);
        assertThat(q.direction()).isEqualTo(RecallDirection.MEANING_TO_TERM);
        assertThat(q.prompts()).hasSize(1);
        assertThat(q.prompts().get(0).promptSide()).isEqualTo("MEANING");
        assertThat(q.prompts().get(0).promptText()).isEqualTo("見捨てる");
        // 答え（term）がどのフィールドにも出現しない。
        assertThat(q.toString()).doesNotContain("abandon");
    }

    @Test
    @DisplayName("AC-51: TERM_TO_MEANING では cue=term のみ。meaning（答え）が一切載らない")
    void termToMeaning_cueIsTermOnly() {
        JsonNode content = contentWithTermCard("abandon", "見捨てる");

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(content, RecallDirection.TERM_TO_MEANING);

        assertThat(quiz).hasSize(1);
        ReflectionEntryResponse.MaskedCardQuiz q = quiz.get(0);
        assertThat(q.prompts().get(0).promptSide()).isEqualTo("TERM");
        assertThat(q.prompts().get(0).promptText()).isEqualTo("abandon");
        assertThat(q.toString()).doesNotContain("見捨てる");
    }

    @Test
    @DisplayName("AC-51: direction=null（today=null 等）なら cue を一切出さない（fail-closed）")
    void nullDirection_emptyAndNoLeak() {
        JsonNode content = contentWithTermCard("abandon", "見捨てる");

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(content, null);

        assertThat(quiz).isEmpty();
    }

    @Test
    @DisplayName("AC-51/AC-56: OUTLINE section（type 欠落=OUTLINE 含む）は cue を出さない")
    void outlineSection_noCue() {
        // type を付けない（=OUTLINE 扱い）section に cards を紛れ込ませても cue を出さない。
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "x");
        ArrayNode sections = root.putArray("sections");
        ObjectNode legacy = sections.addObject();
        legacy.put("heading", "見出し");
        ArrayNode cards = legacy.putArray("cards"); // type 欠落=OUTLINE のため無視されるべき
        cards.addObject().put("term", "secret-term").put("meaning", "secret-meaning");

        List<ReflectionEntryResponse.MaskedCardQuiz> quizMt =
                extractor.extractCardQuiz(root, RecallDirection.MEANING_TO_TERM);
        List<ReflectionEntryResponse.MaskedCardQuiz> quizTm =
                extractor.extractCardQuiz(root, RecallDirection.TERM_TO_MEANING);

        assertThat(quizMt).isEmpty();
        assertThat(quizTm).isEmpty();
        // どちらの語も漏れない。
        assertThat(quizMt.toString()).doesNotContain("secret-term", "secret-meaning");
        assertThat(quizTm.toString()).doesNotContain("secret-term", "secret-meaning");
    }

    @Test
    @DisplayName("AC-51: cards 欠落の TERM_CARD は prompts 空（答えが無いので漏れない）")
    void termCardWithoutCards_emptyPrompts() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "x");
        ArrayNode sections = root.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        card.put("heading", "今日の単語");
        // cards 欠落

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(root, RecallDirection.MEANING_TO_TERM);

        // cards 欠落 → section 自体が prompts 抽出対象にならない（cardQuiz 空）。
        assertThat(quiz).isEmpty();
    }

    @Test
    @DisplayName("AC-51: cue 側が空の場合はそのカードを prompt にしない（空 cue でも答えは載せない）")
    void emptyCueSide_skipsCardNoLeak() {
        // MEANING_TO_TERM で meaning（cue）が空・term（答え）あり → そのカードは prompt を作らない。
        JsonNode content = contentWithTermCard("answer-term", "");

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(content, RecallDirection.MEANING_TO_TERM);

        assertThat(quiz).hasSize(1);
        assertThat(quiz.get(0).prompts()).isEmpty();
        // 答え（term）は絶対に漏れない。
        assertThat(quiz.get(0).toString()).doesNotContain("answer-term");
    }

    @Test
    @DisplayName("AC-51: term 欠落カードでも TERM_TO_MEANING で答え(meaning)が漏れない")
    void termMissing_termToMeaning_noLeak() {
        // TERM_TO_MEANING で cue=term が欠落・meaning（答え）あり → prompt を作らず meaning も漏らさない。
        JsonNode content = contentWithTermCard(null, "answer-meaning");

        List<ReflectionEntryResponse.MaskedCardQuiz> quiz =
                extractor.extractCardQuiz(content, RecallDirection.TERM_TO_MEANING);

        assertThat(quiz).hasSize(1);
        assertThat(quiz.get(0).prompts()).isEmpty();
        assertThat(quiz.get(0).toString()).doesNotContain("answer-meaning");
    }

    @Test
    @DisplayName("AC-51: null content / 非オブジェクトは空（fail-closed）")
    void nullContent_empty() {
        assertThat(extractor.extractCardQuiz(null, RecallDirection.MEANING_TO_TERM)).isEmpty();
        assertThat(extractor.extractCardQuiz(objectMapper.createArrayNode(),
                RecallDirection.MEANING_TO_TERM)).isEmpty();
    }
}
