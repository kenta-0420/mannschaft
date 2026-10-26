package com.mannschaft.app.budget.dto;

/**
 * 報告書ダウンロードURL レスポンス。
 */
public record DownloadUrlResponse(
        Long reportId,
        String downloadUrl,
        long expiresInSeconds
) {
}
