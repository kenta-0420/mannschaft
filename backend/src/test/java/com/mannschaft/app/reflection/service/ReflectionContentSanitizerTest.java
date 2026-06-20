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
}
