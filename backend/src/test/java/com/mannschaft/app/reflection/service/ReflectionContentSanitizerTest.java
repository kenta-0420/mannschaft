package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReflectionContentSanitizer} 単体テスト（F06.5・§2.3）。
 *
 * <p>カバー: script/on* 除去（XSS 二重防御）/ main_theme 必須 / sections 件数上限 / 字数上限。</p>
 */
@DisplayName("ReflectionContentSanitizer 単体テスト（§2.3）")
class ReflectionContentSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReflectionContentSanitizer sanitizer = new ReflectionContentSanitizer(objectMapper);

    @Test
    @DisplayName("script タグを除去して純テキスト化する")
    void stripsScript() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "二次関数<script>alert(1)</script>");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).doesNotContain("<script>");
        assertThat(json).contains("二次関数");
    }

    @Test
    @DisplayName("main_theme が空白なら 400（CONTENT_INVALID）")
    void blankMainTheme_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "   ");

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("main_theme 欠落（非オブジェクト含む）なら 400")
    void missingMainTheme_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("free_note", "メモのみ");

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("sections が上限（30）超なら 400")
    void tooManySections_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "単元");
        ArrayNode sections = content.putArray("sections");
        for (int i = 0; i < ReflectionConstants.MAX_SECTIONS + 1; i++) {
            ObjectNode s = sections.addObject();
            s.put("heading", "見出し" + i);
        }

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("main_theme が字数上限（200）超なら 400")
    void mainThemeTooLong_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "あ".repeat(ReflectionConstants.MAX_HEADING_LENGTH + 1));

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("正常系: sections/subsections を含む構造をサニタイズしてシリアライズ")
    void validStructure_serialized() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "二次関数の最大最小");
        ArrayNode sections = content.putArray("sections");
        ObjectNode section = sections.addObject();
        section.put("heading", "平方完成");
        ArrayNode subs = section.putArray("subsections");
        ObjectNode sub = subs.addObject();
        sub.put("sub_heading", "基本形");
        sub.put("detail", "頂点を読む<b>強調</b>");
        sub.put("supplement", "a>0 で下に凸");
        content.put("free_note", "所感");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).contains("二次関数の最大最小");
        assertThat(json).contains("平方完成");
        assertThat(json).doesNotContain("<b>");
    }

    @Test
    @DisplayName("parse: 保存済み JSON 文字列を JsonNode に復元する")
    void parse_restores() {
        String json = "{\"main_theme\":\"x\"}";
        assertThat(sanitizer.parse(json).get("main_theme").asText()).isEqualTo("x");
    }

    // ===== Phase 4: 暗記カード（TERM_CARD）バリデーション（§13-A-3） =====

    @Test
    @DisplayName("AC-48: section.type に OUTLINE/TERM_CARD を指定すると型が保持される")
    void sectionType_roundTrip() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語 Unit 5");
        ArrayNode sections = content.putArray("sections");
        ObjectNode outline = sections.addObject();
        outline.put("type", "OUTLINE");
        outline.put("heading", "ポイント");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        card.put("heading", "今日の単語");
        ArrayNode cards = card.putArray("cards");
        cards.addObject().put("term", "abandon").put("meaning", "見捨てる");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).contains("\"type\":\"OUTLINE\"");
        assertThat(json).contains("\"type\":\"TERM_CARD\"");
        assertThat(json).contains("abandon");
    }

    @Test
    @DisplayName("AC-48/AC-50: section.type 欠落は OUTLINE に正規化される（後方互換）")
    void sectionType_missing_normalizedToOutline() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "単元");
        ArrayNode sections = content.putArray("sections");
        sections.addObject().put("heading", "見出しのみ");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).contains("\"type\":\"OUTLINE\"");
    }

    @Test
    @DisplayName("AC-49: TERM_CARD の cards[] 複数（term/meaning）が保持される")
    void termCard_multipleCards_preserved() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        card.put("heading", "今日の単語");
        ArrayNode cards = card.putArray("cards");
        cards.addObject().put("term", "abandon").put("meaning", "見捨てる");
        cards.addObject().put("term", "ambiguous").put("meaning", "曖昧な");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).contains("abandon");
        assertThat(json).contains("見捨てる");
        assertThat(json).contains("ambiguous");
        assertThat(json).contains("曖昧な");
    }

    @Test
    @DisplayName("AC-50: type/cards 欠落の旧形 JSON が OUTLINE・cards 無しで壊れず正規化される（後方互換番人）")
    void legacyJson_typeAndCardsMissing_notBroken() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "既存エントリ");
        ArrayNode sections = content.putArray("sections");
        ObjectNode section = sections.addObject();
        section.put("heading", "平方完成");
        ArrayNode subs = section.putArray("subsections");
        subs.addObject().put("sub_heading", "基本形").put("detail", "頂点を読む");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).contains("\"type\":\"OUTLINE\"");
        assertThat(json).contains("平方完成");
        assertThat(json).contains("基本形");
        // cards を勝手に書き足さない（欠落のまま素通し・§13-A-3）。
        assertThat(json).doesNotContain("\"cards\"");
    }

    @Test
    @DisplayName("AC-54: cards が 51 枚なら 400（REFLECTION_007）")
    void termCard_tooManyCards_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        ArrayNode cards = card.putArray("cards");
        for (int i = 0; i < ReflectionConstants.MAX_CARDS_PER_SECTION + 1; i++) {
            cards.addObject().put("term", "t" + i).put("meaning", "m" + i);
        }

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("AC-54: card.term が 201 字なら 400")
    void termCard_termTooLong_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        ArrayNode cards = card.putArray("cards");
        cards.addObject()
                .put("term", "あ".repeat(ReflectionConstants.MAX_CARD_TERM_LENGTH + 1))
                .put("meaning", "意味");

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("AC-54: card.meaning が 201 字なら 400")
    void termCard_meaningTooLong_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        ArrayNode cards = card.putArray("cards");
        cards.addObject()
                .put("term", "abandon")
                .put("meaning", "あ".repeat(ReflectionConstants.MAX_CARD_MEANING_LENGTH + 1));

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("AC-54: section.type が不正値なら 400")
    void sectionType_invalid_throws() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode section = sections.addObject();
        section.put("type", "QUIZ"); // 許可リスト外
        section.put("heading", "x");

        assertThatThrownBy(() -> sanitizer.sanitizeAndSerialize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    @Test
    @DisplayName("card.term の HTML は全て平文化される（XSS 二重防御・§13-A-3）")
    void termCard_htmlStripped() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        ArrayNode sections = content.putArray("sections");
        ObjectNode card = sections.addObject();
        card.put("type", "TERM_CARD");
        ArrayNode cards = card.putArray("cards");
        cards.addObject().put("term", "abandon<script>alert(1)</script>").put("meaning", "見捨てる");

        String json = sanitizer.sanitizeAndSerialize(content);

        assertThat(json).doesNotContain("<script>");
        assertThat(json).contains("abandon");
    }
}
