package com.mannschaft.app.common.pdf;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * PDF レスポンス生成ヘルパー。
 * Content-Type, Content-Disposition, Content-Length を自動設定する。
 */
public final class PdfResponseHelper {

    private PdfResponseHelper() {}

    /**
     * PDF バイト配列から ResponseEntity を生成する。
     *
     * @param pdfBytes PDF データ
     * @param fileName ファイル名（日本語可、RFC 5987 でエンコード）
     * @return ResponseEntity<byte[]>
     */
    public static ResponseEntity<byte[]> toResponse(byte[] pdfBytes, String fileName) {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        // ASCII フォールバック: 非ASCII文字を除去
        String asciiFileName = fileName.replaceAll("[^\\x20-\\x7E]", "_");

        String contentDisposition = "attachment; "
                + "filename=\"" + asciiFileName + "\"; "
                + "filename*=UTF-8''" + encodedFileName;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
    }
}
