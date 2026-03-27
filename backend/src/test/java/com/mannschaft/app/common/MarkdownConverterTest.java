package com.mannschaft.app.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownConverter} の単体テスト。
 * Markdown → HTML 変換のエッジケースを検証する。
 */
@DisplayName("MarkdownConverter 単体テスト")
class MarkdownConverterTest {

    // ========================================
    // toHtml
    // ========================================

    @Nested
    @DisplayName("toHtml")
    class ToHtml {

        @Test
        @DisplayName("正常系: Markdownテキストが正しくHTMLに変換される")
        void toHtml_正常なMarkdown_HTMLに変換される() {
            // When
            String result = MarkdownConverter.toHtml("**太字**");

            // Then
            assertThat(result).contains("<strong>太字</strong>");
        }

        @Test
        @DisplayName("境界値: nullの場合は空文字を返す")
        void toHtml_null_空文字を返す() {
            // When
            String result = MarkdownConverter.toHtml(null);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("境界値: 空文字の場合は空文字を返す")
        void toHtml_空文字_空文字を返す() {
            // When
            String result = MarkdownConverter.toHtml("");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("境界値: 空白のみの場合は空文字を返す")
        void toHtml_空白のみ_空文字を返す() {
            // When
            String result = MarkdownConverter.toHtml("   ");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 見出しMarkdownがh1タグに変換される")
        void toHtml_見出し_h1に変換される() {
            // When
            String result = MarkdownConverter.toHtml("# 見出し");

            // Then
            assertThat(result).contains("<h1>見出し</h1>");
        }

        @Test
        @DisplayName("正常系: リストMarkdownがulタグに変換される")
        void toHtml_リスト_ulに変換される() {
            // When
            String result = MarkdownConverter.toHtml("- 項目1\n- 項目2");

            // Then
            assertThat(result).contains("<ul>");
            assertThat(result).contains("<li>項目1</li>");
        }
    }
}
