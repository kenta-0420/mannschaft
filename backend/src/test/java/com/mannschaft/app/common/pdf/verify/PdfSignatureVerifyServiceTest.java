package com.mannschaft.app.common.pdf.verify;

import com.mannschaft.app.common.pdf.InternalPdfSigningProperties;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.SignedPdfResult;
import com.mannschaft.app.config.PdfFontConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfSignatureVerifyService} の単体テスト。
 *
 * <p>{@link PdfGeneratorService} を実物で組み立てて HMAC ロジックの整合性を end-to-end で検証する
 * （Mock 化すると署名・検証ロジックの整合性が確認できないため）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PdfSignatureVerifyService 単体テスト")
class PdfSignatureVerifyServiceTest {

    private static final String TEST_KEY = "test-verify-svc-signing-key-fixed-xxxxx";

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private PdfFontConfig pdfFontConfig;

    private PdfGeneratorService pdfGeneratorService;
    private PdfSignatureVerifyService verifyService;

    @BeforeEach
    void setUp() {
        pdfGeneratorService = new PdfGeneratorService(
                templateEngine, pdfFontConfig, new InternalPdfSigningProperties(TEST_KEY));
        verifyService = new PdfSignatureVerifyService(pdfGeneratorService);
    }

    @Nested
    @DisplayName("正常系")
    class Valid {

        @Test
        @DisplayName("正常系: sign 結果をそのまま検証すると valid=true")
        void sign結果は常にvalid() {
            byte[] pdf = "trusted-pdf".getBytes();
            String subjectId = "covenant-uuid-001";
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdf, subjectId);

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    subjectId,
                    Base64.getEncoder().encodeToString(signed.pdf()),
                    signed.hashSha256(),
                    signed.timestampToken());

            PdfSignatureVerifyResponse res = verifyService.verify(req);

            assertThat(res.valid()).isTrue();
            assertThat(res.hashMatch()).isTrue();
            assertThat(res.tokenValid()).isTrue();
            assertThat(res.computedHash()).isEqualTo(signed.hashSha256());
            assertThat(res.verifiedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("改ざん検知")
    class TamperDetection {

        @Test
        @DisplayName("PDF バイト改ざん: hashMatch=false → valid=false")
        void PDFバイト改ざん_hashMismatch() {
            byte[] pdf = "trusted-pdf".getBytes();
            String subjectId = "covenant-uuid-002";
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdf, subjectId);

            // PDF バイトだけ改ざん
            byte[] tampered = "tampered-pdf".getBytes();
            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    subjectId,
                    Base64.getEncoder().encodeToString(tampered),
                    signed.hashSha256(),    // 期待値は元の hash のまま
                    signed.timestampToken());

            PdfSignatureVerifyResponse res = verifyService.verify(req);

            assertThat(res.valid()).isFalse();
            assertThat(res.hashMatch()).isFalse();
            // expectedHash + expectedToken が整合しているため tokenValid は true
            assertThat(res.tokenValid()).isTrue();
        }

        @Test
        @DisplayName("subjectId 改ざん: tokenValid=false → valid=false")
        void subjectId改ざん_tokenInvalid() {
            byte[] pdf = "trusted-pdf".getBytes();
            String subjectId = "covenant-uuid-003";
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdf, subjectId);

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "different-subject-id",
                    Base64.getEncoder().encodeToString(signed.pdf()),
                    signed.hashSha256(),
                    signed.timestampToken());

            PdfSignatureVerifyResponse res = verifyService.verify(req);

            assertThat(res.valid()).isFalse();
            assertThat(res.hashMatch()).isTrue();
            assertThat(res.tokenValid()).isFalse();
        }

        @Test
        @DisplayName("expectedHash 改ざん: hashMatch=false + tokenValid=false")
        void expectedHash改ざん_両方失敗() {
            byte[] pdf = "trusted-pdf".getBytes();
            String subjectId = "covenant-uuid-004";
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdf, subjectId);

            String tamperedHash = "0".repeat(64);
            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    subjectId,
                    Base64.getEncoder().encodeToString(signed.pdf()),
                    tamperedHash,
                    signed.timestampToken());

            PdfSignatureVerifyResponse res = verifyService.verify(req);

            assertThat(res.valid()).isFalse();
            assertThat(res.hashMatch()).isFalse();
            // expectedHash が改ざんされると recompute も合わなくなる
            assertThat(res.tokenValid()).isFalse();
        }

        @Test
        @DisplayName("token 形式不正（ドットなし）: tokenValid=false")
        void token形式不正_tokenInvalid() {
            byte[] pdf = "trusted-pdf".getBytes();
            String subjectId = "covenant-uuid-005";
            SignedPdfResult signed = pdfGeneratorService.signWithInternalToken(pdf, subjectId);

            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    subjectId,
                    Base64.getEncoder().encodeToString(signed.pdf()),
                    signed.hashSha256(),
                    "invalid-token-without-dot");

            PdfSignatureVerifyResponse res = verifyService.verify(req);

            assertThat(res.valid()).isFalse();
            assertThat(res.tokenValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("Base64 デコード失敗")
    class Base64DecodeFailure {

        @Test
        @DisplayName("不正な Base64 で PDF_006 例外")
        void 不正Base64_PDF006例外() {
            PdfSignatureVerifyRequest req = new PdfSignatureVerifyRequest(
                    "subj", "!!!invalid-base64!!!", "0".repeat(64), "abc.123");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> verifyService.verify(req))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                    .satisfies(ex -> assertThat(
                            ((com.mannschaft.app.common.BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PDF_006"));
        }
    }
}
