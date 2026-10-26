package com.mannschaft.app.common.excel;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Excel レスポンス生成ヘルパー。
 *
 * <p>{@link com.mannschaft.app.common.pdf.PdfResponseHelper} の Excel 版。
 * Content-Type は {@code application/vnd.openxmlformats-officedocument.spreadsheetml.sheet} を設定し、
 * Content-Disposition には RFC 5987 形式の {@code filename*} と
 * ASCII フォールバック {@code filename} を併記して日本語ファイル名を扱う。
 */
@Component
public class ExcelResponseHelper {

    /** Excel (XLSX) MIME タイプ。 */
    public static final MediaType EXCEL_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /**
     * Excel バイト配列から ResponseEntity を生成する。
     *
     * @param excelBytes Excel データ
     * @param fileName   ファイル名（日本語可、RFC 5987 でエンコード）
     * @return {@code ResponseEntity<byte[]>}
     */
    public ResponseEntity<byte[]> toResponse(byte[] excelBytes, String fileName) {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        // ASCII フォールバック: 非ASCII文字を除去
        String asciiFileName = fileName.replaceAll("[^\\x20-\\x7E]", "_");

        String contentDisposition = "attachment; "
                + "filename=\"" + asciiFileName + "\"; "
                + "filename*=UTF-8''" + encodedFileName;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentType(EXCEL_MEDIA_TYPE)
                .contentLength(excelBytes.length)
                .body(excelBytes);
    }
}
