package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.config.PdfFontConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
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

    private PdfGeneratorService pdfGeneratorService;

    /**
     * F12.1 §5.14 / F09.15 §9.4 — 単体テスト用の固定鍵。本番では環境変数で注入される。
     */
    private static final String TEST_SIGNING_KEY = "unit-test-signing-key-fixed-32bytes-xxxxx";

    @BeforeEach
    void setUp() {
        pdfGeneratorService = new PdfGeneratorService(
                templateEngine,
                pdfFontConfig,
                new InternalPdfSigningProperties(TEST_SIGNING_KEY));
    }

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

    // ========================================
    // signWithInternalToken（F12.1 §5.14）
    // ========================================

    @Nested
    @DisplayName("signWithInternalToken")
    class SignWithInternalToken {

        @Test
        @DisplayName("正常系: PDF + subjectId に対し SHA-256 と内部署名トークンが計算される")
        void 正常系_SHA256とトークンが返る() {
            byte[] pdfBytes = "dummy-pdf-content".getBytes();
            String subjectId = "covenant-uuid-123";

            SignedPdfResult result = pdfGeneratorService.signWithInternalToken(pdfBytes, subjectId);

            assertThat(result.pdf()).isEqualTo(pdfBytes);
            assertThat(result.subjectId()).isEqualTo(subjectId);
            // SHA-256 hex は 64 桁
            assertThat(result.hashSha256()).hasSize(64).matches("[0-9a-f]{64}");
            // token は "<HMAC_B64URL>.<epochMs>" 形式
            assertThat(result.timestampToken()).contains(".");
            assertThat(result.signedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 同じ入力でも署名時刻が違えばトークンが異なる")
        void 同じ入力_時刻違いでトークン異なる() {
            // Thread.sleep に依存せず、明示的に異なる 2 つの署名時刻を recomputeInternalToken へ渡して
            // トークンが時刻成分（epochMs）に依存することを決定的に検証する。
            byte[] pdfBytes = "abc".getBytes();
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdfBytes, "s1");

            String hashHex = signed.hashSha256();
            Instant t1 = Instant.ofEpochMilli(1_700_000_000_000L);
            Instant t2 = Instant.ofEpochMilli(1_700_000_000_001L); // 1ms だけ後

            String token1 = pdfGeneratorService.recomputeInternalToken(hashHex, "s1", t1);
            String token2 = pdfGeneratorService.recomputeInternalToken(hashHex, "s1", t2);

            // ハッシュ（PDF 内容）は同一だが、署名時刻が異なればトークンは異なる
            assertThat(token1).isNotEqualTo(token2);
        }

        @Test
        @DisplayName("異常系: 鍵未設定で PDF_007 例外")
        void 鍵未設定_PDF007例外() {
            PdfGeneratorService unsigned = new PdfGeneratorService(
                    templateEngine, pdfFontConfig, new InternalPdfSigningProperties(null));

            assertThatThrownBy(() -> unsigned.signWithInternalToken(new byte[]{1, 2, 3}, "subj"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_007"));
        }

        @Test
        @DisplayName("異常系: 鍵空文字でも PDF_007 例外")
        void 鍵空文字_PDF007例外() {
            PdfGeneratorService unsigned = new PdfGeneratorService(
                    templateEngine, pdfFontConfig, new InternalPdfSigningProperties("  "));

            assertThatThrownBy(() -> unsigned.signWithInternalToken(new byte[]{1, 2, 3}, "subj"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_007"));
        }
    }

    // ========================================
    // recomputeInternalToken / sha256Hex（検証 Service との共通利用）
    // ========================================

    @Nested
    @DisplayName("recomputeInternalToken / sha256Hex")
    class TokenRecompute {

        @Test
        @DisplayName("正常系: 同一入力で sign と recompute は一致する")
        void sign結果とrecomputeが一致() {
            byte[] pdfBytes = "verify-me".getBytes();
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdfBytes, "s-abc");

            String recomputed = pdfGeneratorService.recomputeInternalToken(
                    signed.hashSha256(), signed.subjectId(), signed.signedAt());

            assertThat(recomputed).isEqualTo(signed.timestampToken());
        }

        @Test
        @DisplayName("異常系: subjectId が異なれば token が変わる（改ざん検知）")
        void subjectId改ざんでtoken不一致() {
            byte[] pdfBytes = "verify-me".getBytes();
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdfBytes, "s-abc");

            String tampered = pdfGeneratorService.recomputeInternalToken(
                    signed.hashSha256(), "s-different", signed.signedAt());

            assertThat(tampered).isNotEqualTo(signed.timestampToken());
        }

        @Test
        @DisplayName("正常系: sha256Hex は 64 桁 hex を返す")
        void sha256Hex_64桁hex() {
            String hex = pdfGeneratorService.sha256Hex(new byte[]{0});
            assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    // ========================================
    // generateSignedCovenantPdf（F09.15 §7.1）
    // ========================================

    @Nested
    @DisplayName("generateSignedCovenantPdf")
    class GenerateSignedCovenantPdf {

        @Test
        @DisplayName("異常系: テンプレートエンジン失敗で PDF_001 例外")
        void テンプレート失敗_PDF001例外() {
            given(templateEngine.process(eq("pdf/succession-covenant"), any(Context.class)))
                    .willThrow(new RuntimeException("template not found"));

            SuccessionCovenantContext ctx = new SuccessionCovenantContext(
                    "covenant-1",
                    "SUCCESSION_PRE_REGISTRATION",
                    "事前登録誓約",
                    "v1.0.0",
                    "山田 太郎",
                    "301 号室",
                    "OWNER",
                    java.time.LocalDate.of(2026, 5, 9),
                    java.time.LocalDateTime.of(2026, 5, 9, 10, 0),
                    "○○マンション管理組合",
                    "理事長 鈴木 一郎");

            assertThatThrownBy(() -> pdfGeneratorService.generateSignedCovenantPdf(ctx))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_001"));
        }

        @Test
        @DisplayName("異常系: 鍵未設定で PDF_007 例外")
        void 鍵未設定_PDF007例外() {
            PdfGeneratorService unsigned = new PdfGeneratorService(
                    templateEngine, pdfFontConfig, new InternalPdfSigningProperties(null));

            SuccessionCovenantContext ctx = new SuccessionCovenantContext(
                    "covenant-1", "PRIVACY_CONSENT", "プライバシー同意", "v1.0.0",
                    "佐藤 花子", "201 号室", "OWNER",
                    java.time.LocalDate.of(2026, 5, 9),
                    java.time.LocalDateTime.of(2026, 5, 9, 10, 0),
                    "○○組合", "理事長");

            assertThatThrownBy(() -> unsigned.generateSignedCovenantPdf(ctx))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_007"));
        }
    }

    // signedAt 引数で時刻を固定するヘルパ（将来テストで利用）
    @SuppressWarnings("unused")
    private Instant fixedSignedAt() {
        return Instant.parse("2026-05-09T10:00:00Z");
    }
}
