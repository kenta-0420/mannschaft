package com.mannschaft.app.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HtmlSanitizer} 単体テスト。
 *
 * <p>F02.5 publish-daily の extra_comment サニタイズ要件（設計書 §5 重要な判定ロジック）
 * を満たすことを検証する。</p>
 */
@DisplayName("HtmlSanitizer 単体テスト")
class HtmlSanitizerTest {

    @Nested
    @DisplayName("sanitizePlainText")
    class SanitizePlainTextTest {

        @Test
        @DisplayName("純テキストはそのまま通過する")
        void plainText_passesThrough() {
            String input = "今日はよく動けた。明日も頑張る";
            assertThat(HtmlSanitizer.sanitizePlainText(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("<script> タグは完全に除去される（XSS 対策の核）")
        void scriptTag_isRemoved() {
            String input = "<script>alert(1)</script>";
            String result = HtmlSanitizer.sanitizePlainText(input);
            assertThat(result).doesNotContain("<script");
            assertThat(result).doesNotContain("alert(1)");
        }

        @Test
        @DisplayName("null 入力は null を返す")
        void nullInput_returnsNull() {
            assertThat(HtmlSanitizer.sanitizePlainText(null)).isNull();
        }

        @Test
        @DisplayName("空文字入力は空文字を返す")
        void emptyInput_returnsEmpty() {
            assertThat(HtmlSanitizer.sanitizePlainText("")).isEmpty();
        }

        @Test
        @DisplayName("<a> タグも plain テキスト化で除去される")
        void anchorTag_isRemoved() {
            String input = "詳細は <a href=\"http://evil\">ここ</a> を参照";
            String result = HtmlSanitizer.sanitizePlainText(input);
            assertThat(result).doesNotContain("<a");
            assertThat(result).doesNotContain("href");
            // 中身のテキストは残る
            assertThat(result).contains("ここ");
        }
    }

    @Nested
    @DisplayName("sanitizeBasic")
    class SanitizeBasicTest {

        @Test
        @DisplayName("<b> タグは basic safelist で保持される")
        void boldTag_isKept() {
            String input = "<b>重要</b>";
            String result = HtmlSanitizer.sanitizeBasic(input);
            assertThat(result).contains("<b>");
            assertThat(result).contains("重要");
        }

        @Test
        @DisplayName("<script> タグは basic safelist でも除去される")
        void scriptTag_isRemovedInBasic() {
            String input = "<script>alert(1)</script>";
            String result = HtmlSanitizer.sanitizeBasic(input);
            assertThat(result).doesNotContain("<script");
            assertThat(result).doesNotContain("alert(1)");
        }
    }
}
