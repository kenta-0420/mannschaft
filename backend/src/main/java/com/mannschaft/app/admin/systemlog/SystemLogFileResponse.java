package com.mannschaft.app.admin.systemlog;

/**
 * システムログファイルの一覧レスポンス DTO。
 *
 * @param type        ログの種別（"slow-query" | "ssr-error"）
 * @param date        ログ日付（"YYYY-MM-DD" 形式）
 * @param fileName    R2 上のファイル名
 * @param sizeBytes   ファイルサイズ（バイト）
 * @param downloadUrl Presigned ダウンロード URL（有効期限 15 分）
 */
public record SystemLogFileResponse(
        String type,
        String date,
        String fileName,
        long sizeBytes,
        String downloadUrl
) {}
