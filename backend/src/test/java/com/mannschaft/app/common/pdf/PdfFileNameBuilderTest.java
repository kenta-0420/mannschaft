package com.mannschaft.app.common.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PdfFileNameBuilder} の単体テスト。
 * PDFファイル名生成ロジックを検証する。
 */
@DisplayName("PdfFileNameBuilder 単体テスト")
class PdfFileNameBuilderTest {

    // ========================================
    // build
    // ========================================

    @Nested
    @DisplayName("build")
    class Build {

        @Test
        @DisplayName("正常系: 日付・文書種別・識別名でファイル名が生成される")
        void build_全パラメータ指定_正しいファイル名() {
            // When
            String result = PdfFileNameBuilder.of("会員証")
                    .date(LocalDate.of(2026, 3, 26))
                    .identifier("yamada")
                    .build();

            // Then
            assertThat(result).isEqualTo("20260326_会員証_yamada.pdf");
        }

        @Test
        @DisplayName("正常系: 識別名なしでファイル名が生成される")
        void build_識別名なし_正しいファイル名() {
            // When
            String result = PdfFileNameBuilder.of("請求書")
                    .date(LocalDate.of(2026, 1, 15))
                    .build();

            // Then
            assertThat(result).isEqualTo("20260115_請求書.pdf");
        }

        @Test
        @DisplayName("正常系: 識別名が空白の場合は省略される")
        void build_識別名空白_省略される() {
            // When
            String result = PdfFileNameBuilder.of("報告書")
                    .date(LocalDate.of(2026, 6, 1))
                    .identifier("   ")
                    .build();

            // Then
            assertThat(result).isEqualTo("20260601_報告書.pdf");
        }

        @Test
        @DisplayName("正常系: 禁止文字がアンダースコアに置換される")
        void build_禁止文字_アンダースコアに置換() {
            // When
            String result = PdfFileNameBuilder.of("報告書/2026")
                    .date(LocalDate.of(2026, 3, 1))
                    .build();

            // Then
            assertThat(result).isEqualTo("20260301_報告書_2026.pdf");
        }

        @Test
        @DisplayName("正常系: documentTypeにコロンが含まれる場合はアンダースコアに置換")
        void build_コロン含む_アンダースコアに置換() {
            // When
            String result = PdfFileNameBuilder.of("doc:type")
                    .date(LocalDate.of(2026, 3, 1))
                    .build();

            // Then
            assertThat(result).isEqualTo("20260301_doc_type.pdf");
        }

        @Test
        @DisplayName("異常系: dateがnullでNullPointerException")
        void build_dateがnull_例外() {
            // When / Then
            assertThatThrownBy(() ->
                    PdfFileNameBuilder.of("会員証").build()
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("異常系: documentTypeがnullでNullPointerException")
        void build_documentTypeがnull_例外() {
            // When / Then
            assertThatThrownBy(() -> PdfFileNameBuilder.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("境界値: 100文字を超えるファイル名は切り詰められ.pdfで終わる")
        void build_長いファイル名_切り詰め() {
            // Given: 非常に長い識別名
            String longIdentifier = "a".repeat(200);

            // When
            String result = PdfFileNameBuilder.of("文書")
                    .date(LocalDate.of(2026, 3, 1))
                    .identifier(longIdentifier)
                    .build();

            // Then
            assertThat(result.length()).isLessThanOrEqualTo(100);
            assertThat(result).endsWith(".pdf");
        }
    }

    // ========================================
    // buildEncoded
    // ========================================

    @Nested
    @DisplayName("buildEncoded")
    class BuildEncoded {

        @Test
        @DisplayName("正常系: URLエンコードされたファイル名が返る")
        void buildEncoded_日本語ファイル名_URLエンコード() {
            // When
            String result = PdfFileNameBuilder.of("会員証")
                    .date(LocalDate.of(2026, 3, 26))
                    .buildEncoded();

            // Then
            assertThat(result).isNotBlank();
            assertThat(result).doesNotContain(" ");
            // +がエンコードされ%20になること
            assertThat(result).doesNotContain("+");
        }

        @Test
        @DisplayName("正常系: ASCII文字のみのファイル名はそのまま返る")
        void buildEncoded_ASCII文字のみ_そのまま返る() {
            // When
            String result = PdfFileNameBuilder.of("report")
                    .date(LocalDate.of(2026, 3, 26))
                    .identifier("yamada")
                    .buildEncoded();

            // Then
            assertThat(result).isEqualTo("20260326_report_yamada.pdf");
        }
    }
}
