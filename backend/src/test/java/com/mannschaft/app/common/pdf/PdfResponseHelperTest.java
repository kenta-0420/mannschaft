package com.mannschaft.app.common.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfResponseHelper} の単体テスト。
 * PDFレスポンス生成ロジックを検証する。
 */
@DisplayName("PdfResponseHelper 単体テスト")
class PdfResponseHelperTest {

    // ========================================
    // toResponse
    // ========================================

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("正常系: Content-TypeがPDFになる")
        void toResponse_ContentType_PDF() {
            // Given
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF
            String fileName = "report.pdf";

            // When
            ResponseEntity<byte[]> response = PdfResponseHelper.toResponse(pdfBytes, fileName);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        }

        @Test
        @DisplayName("正常系: Content-Lengthがバイト配列の長さになる")
        void toResponse_ContentLength_バイト長() {
            // Given
            byte[] pdfBytes = new byte[1024];
            String fileName = "test.pdf";

            // When
            ResponseEntity<byte[]> response = PdfResponseHelper.toResponse(pdfBytes, fileName);

            // Then
            assertThat(response.getHeaders().getContentLength()).isEqualTo(1024L);
        }

        @Test
        @DisplayName("正常系: Content-DispositionにASCIIフォールバック名とUTF-8エンコード名が含まれる")
        void toResponse_ContentDisposition_ASCIIとUTF8() {
            // Given
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};
            String fileName = "report.pdf";

            // When
            ResponseEntity<byte[]> response = PdfResponseHelper.toResponse(pdfBytes, fileName);

            // Then
            String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(contentDisposition).isNotNull();
            assertThat(contentDisposition).contains("attachment");
            assertThat(contentDisposition).contains("filename=\"report.pdf\"");
            assertThat(contentDisposition).contains("filename*=UTF-8''");
        }

        @Test
        @DisplayName("正常系: 日本語ファイル名でもレスポンスが正しく生成される")
        void toResponse_日本語ファイル名_正常生成() {
            // Given
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};
            String fileName = "会員証.pdf";

            // When
            ResponseEntity<byte[]> response = PdfResponseHelper.toResponse(pdfBytes, fileName);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(contentDisposition).isNotNull();
            assertThat(contentDisposition).contains("attachment");
            // ASCII名は非ASCII文字がアンダースコアに置換される
            assertThat(contentDisposition).contains("filename=\"");
        }

        @Test
        @DisplayName("正常系: PDFバイト配列がレスポンスボディに含まれる")
        void toResponse_バイト配列_ボディに含まれる() {
            // Given
            byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D};
            String fileName = "test.pdf";

            // When
            ResponseEntity<byte[]> response = PdfResponseHelper.toResponse(pdfBytes, fileName);

            // Then
            assertThat(response.getBody()).isEqualTo(pdfBytes);
        }
    }
}
