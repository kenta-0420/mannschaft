package com.mannschaft.app.common.pdf.verify;

import jakarta.validation.constraints.NotBlank;

/**
 * 内部署名トークン検証 API（F12.1 §5.14 / F09.15 §9.4）のリクエスト DTO。
 *
 * @param subjectId     署名対象識別子（例: succession_covenants.id）
 * @param pdfBase64     検証対象 PDF の Base64 エンコード文字列
 * @param expectedHash  期待される SHA-256 ハッシュ（hex 小文字 64 桁）
 * @param expectedToken 期待される内部署名トークン（HMAC-Base64URL + "." + epochMs 形式）
 */
public record PdfSignatureVerifyRequest(
        @NotBlank String subjectId,
        @NotBlank String pdfBase64,
        @NotBlank String expectedHash,
        @NotBlank String expectedToken
) {
}
