package com.mannschaft.app.skill.dto;

/**
 * 証明書アップロード用Pre-signed URLレスポンス。
 */
public record UploadUrlResponse(
        String uploadUrl,
        String s3Key
) {
}
