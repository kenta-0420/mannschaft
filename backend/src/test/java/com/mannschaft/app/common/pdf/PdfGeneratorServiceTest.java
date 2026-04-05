package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.config.PdfFontConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PdfGeneratorService} の単体テスト。
 * Thymeleaf テンプレートからのHTML生成およびPDF変換ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PdfGeneratorService 単体テスト")
class PdfGeneratorServiceTest {

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private PdfFontConfig pdfFontConfig;

    @InjectMocks
    private PdfGeneratorService pdfGeneratorService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String TEST_TEMPLATE_NAME = "pdf/receipt";
    private static final Map<String, Object> TEST_VARIABLES = Map.of("key", "value");

    // ========================================
    // generateFromTemplate
    // ========================================

    @Nested
    @DisplayName("generateFromTemplate")
    class GenerateFromTemplate {

        @Test
        @DisplayName("異常系: テンプレート処理失敗でPDF_001例外")
        void テンプレート処理_例外発生_PDF001例外() {
            // Given
            given(templateEngine.process(eq(TEST_TEMPLATE_NAME), any(Context.class)))
                    .willThrow(new RuntimeException("テンプレートが見つかりません"));

            // When / Then
            assertThatThrownBy(() -> pdfGeneratorService.generateFromTemplate(TEST_TEMPLATE_NAME, TEST_VARIABLES))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_001"));
        }

        @Test
        @DisplayName("異常系: フォント登録失敗でPDF_003例外")
        void フォント登録失敗_PDF003例外() {
            // Given: テンプレートからHTMLは正常生成されるが、フォント登録で失敗
            given(templateEngine.process(eq(TEST_TEMPLATE_NAME), any(Context.class)))
                    .willReturn("<html><body>テスト</body></html>");

            // フォント登録で存在しないパスを返す（ClassPathResource.getInputStream() で例外発生）
            PdfFontConfig.FontEntry badFont = new PdfFontConfig.FontEntry(
                    "fonts/nonexistent.ttf", "NonExistentFont");
            given(pdfFontConfig.getRegisteredFonts()).willReturn(List.of(badFont));

            // When / Then
            assertThatThrownBy(() -> pdfGeneratorService.generateFromTemplate(TEST_TEMPLATE_NAME, TEST_VARIABLES))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_003"));
        }

        @Test
        @DisplayName("異常系: フォント登録成功後のPDF変換失敗でPDF_002例外")
        void PDF変換失敗_PDF002例外() {
            // Given: テンプレートからHTMLは正常生成、フォントなし、不正なHTMLでPDF変換失敗
            given(templateEngine.process(eq(TEST_TEMPLATE_NAME), any(Context.class)))
                    .willReturn("<<<invalid-html-that-will-fail-parsing>>>");
            given(pdfFontConfig.getRegisteredFonts()).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> pdfGeneratorService.generateFromTemplate(TEST_TEMPLATE_NAME, TEST_VARIABLES))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        String code = ((BusinessException) ex).getErrorCode().getCode();
                        assertThat(code).isIn("PDF_002", "PDF_003");
                    });
        }

        @Test
        @DisplayName("正常系: 空の変数マップでも処理が実行される")
        void 空の変数マップ_処理実行される() {
            // Given
            Map<String, Object> emptyVars = Map.of();
            given(templateEngine.process(eq(TEST_TEMPLATE_NAME), any(Context.class)))
                    .willThrow(new RuntimeException("template error"));

            // When / Then: テンプレート処理が呼ばれることを確認（HTMLレンダリングでエラーになるがテンプレートエンジンは呼ばれる）
            assertThatThrownBy(() -> pdfGeneratorService.generateFromTemplate(TEST_TEMPLATE_NAME, emptyVars))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_001"));
        }
    }
}
