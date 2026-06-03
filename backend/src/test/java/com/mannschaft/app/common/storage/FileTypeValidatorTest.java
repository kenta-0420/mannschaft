package com.mannschaft.app.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileTypeValidator} の単体テスト。
 *
 * <p>ブロックリスト・ホワイトリスト・パラメータ除去の各機能を網羅する。</p>
 */
@DisplayName("FileTypeValidator")
class FileTypeValidatorTest {

    // ─────────────────────────────────────────────────
    // isBlocked
    // ─────────────────────────────────────────────────

    @Nested
    @DisplayName("isBlocked")
    class IsBlockedTest {

        @Test
        @DisplayName("SVG は禁止")
        void svg_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("image/svg+xml")).isTrue();
        }

        @Test
        @DisplayName("text/html は禁止")
        void html_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("text/html")).isTrue();
        }

        @Test
        @DisplayName("application/xhtml+xml は禁止")
        void xhtml_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/xhtml+xml")).isTrue();
        }

        @Test
        @DisplayName("application/javascript は禁止")
        void applicationJavascript_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/javascript")).isTrue();
        }

        @Test
        @DisplayName("text/javascript は禁止")
        void textJavascript_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("text/javascript")).isTrue();
        }

        @Test
        @DisplayName("application/x-php は禁止")
        void php_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/x-php")).isTrue();
        }

        @Test
        @DisplayName("application/x-sh は禁止")
        void sh_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/x-sh")).isTrue();
        }

        @Test
        @DisplayName("application/x-python は禁止")
        void python_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/x-python")).isTrue();
        }

        @Test
        @DisplayName("application/xml は禁止（XXE リスク）")
        void xml_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/xml")).isTrue();
        }

        @Test
        @DisplayName("image/png は禁止されない")
        void png_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("image/png")).isFalse();
        }

        @Test
        @DisplayName("image/jpeg は禁止されない")
        void jpeg_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("image/jpeg")).isFalse();
        }

        @Test
        @DisplayName("application/pdf は禁止されない")
        void pdf_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("application/pdf")).isFalse();
        }

        @Test
        @DisplayName("null は禁止されない（false を返す）")
        void null_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked(null)).isFalse();
        }

        @Test
        @DisplayName("空文字は禁止されない（false を返す）")
        void blank_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("")).isFalse();
        }

        @Test
        @DisplayName("空白のみは禁止されない（false を返す）")
        void whitespace_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("   ")).isFalse();
        }

        @Test
        @DisplayName("パラメータ付き SVG も禁止（image/svg+xml; charset=utf-8）")
        void svg_withParam_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("image/svg+xml; charset=utf-8")).isTrue();
        }

        @Test
        @DisplayName("大文字 SVG も禁止（IMAGE/SVG+XML）")
        void svgUpperCase_isBlocked() {
            assertThat(FileTypeValidator.isBlocked("IMAGE/SVG+XML")).isTrue();
        }
    }

    // ─────────────────────────────────────────────────
    // isAllowed
    // ─────────────────────────────────────────────────

    @Nested
    @DisplayName("isAllowed")
    class IsAllowedTest {

        @Test
        @DisplayName("image/jpeg は ALLOWED_IMAGE_TYPES で許可")
        void jpeg_isAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/jpeg", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("image/png は ALLOWED_IMAGE_TYPES で許可")
        void png_isAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/png", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("image/webp は ALLOWED_IMAGE_TYPES で許可")
        void webp_isAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/webp", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("image/gif は ALLOWED_IMAGE_TYPES で許可")
        void gif_isAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/gif", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("image/heic は ALLOWED_IMAGE_TYPES で許可")
        void heic_isAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/heic", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("image/svg+xml は ALLOWED_IMAGE_TYPES に含まれない（ブロック対象）")
        void svg_isNotAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("image/svg+xml", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isFalse();
        }

        @Test
        @DisplayName("application/pdf は ALLOWED_IMAGE_TYPES に含まれない")
        void pdf_isNotAllowedInImageTypes() {
            assertThat(FileTypeValidator.isAllowed("application/pdf", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isFalse();
        }

        @Test
        @DisplayName("application/pdf は ALLOWED_DOCUMENT_TYPES で許可")
        void pdf_isAllowedInDocumentTypes() {
            assertThat(FileTypeValidator.isAllowed("application/pdf", FileTypeValidator.ALLOWED_DOCUMENT_TYPES)).isTrue();
        }

        @Test
        @DisplayName("text/plain は ALLOWED_DOCUMENT_TYPES で許可")
        void textPlain_isAllowedInDocumentTypes() {
            assertThat(FileTypeValidator.isAllowed("text/plain", FileTypeValidator.ALLOWED_DOCUMENT_TYPES)).isTrue();
        }

        @Test
        @DisplayName("DOCX は ALLOWED_DOCUMENT_TYPES で許可")
        void docx_isAllowedInDocumentTypes() {
            assertThat(FileTypeValidator.isAllowed(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    FileTypeValidator.ALLOWED_DOCUMENT_TYPES)).isTrue();
        }

        @Test
        @DisplayName("video/mp4 は ALLOWED_VIDEO_TYPES で許可")
        void mp4_isAllowedInVideoTypes() {
            assertThat(FileTypeValidator.isAllowed("video/mp4", FileTypeValidator.ALLOWED_VIDEO_TYPES)).isTrue();
        }

        @Test
        @DisplayName("null は許可されない（false を返す）")
        void null_isNotAllowed() {
            assertThat(FileTypeValidator.isAllowed(null, FileTypeValidator.ALLOWED_IMAGE_TYPES)).isFalse();
        }

        @Test
        @DisplayName("空文字は許可されない（false を返す）")
        void blank_isNotAllowed() {
            assertThat(FileTypeValidator.isAllowed("", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────
    // パラメータ除去
    // ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Content-Type パラメータ除去")
    class ParameterStrippingTest {

        @Test
        @DisplayName("パラメータ付き image/png は ALLOWED_IMAGE_TYPES で許可")
        void png_withCharset_isAllowed() {
            assertThat(FileTypeValidator.isAllowed(
                    "image/png; charset=utf-8", FileTypeValidator.ALLOWED_IMAGE_TYPES)).isTrue();
        }

        @Test
        @DisplayName("パラメータ付き image/jpeg は isBlocked で false")
        void jpeg_withParam_isNotBlocked() {
            assertThat(FileTypeValidator.isBlocked("image/jpeg; name=photo.jpg")).isFalse();
        }

        @Test
        @DisplayName("パラメータ付き application/pdf は ALLOWED_DOCUMENT_TYPES で許可")
        void pdf_withParam_isAllowed() {
            assertThat(FileTypeValidator.isAllowed(
                    "application/pdf; version=1.7", FileTypeValidator.ALLOWED_DOCUMENT_TYPES)).isTrue();
        }
    }
}
