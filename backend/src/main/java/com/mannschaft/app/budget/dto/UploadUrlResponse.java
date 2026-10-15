package com.mannschaft.app.budget.dto;

/**
 * 添付ファイルアップロードURL レスポンス。
 */
public record UploadUrlResponse(
        String uploadUrl,
        String s3Key,
        long expiresInSeconds
) {
}
