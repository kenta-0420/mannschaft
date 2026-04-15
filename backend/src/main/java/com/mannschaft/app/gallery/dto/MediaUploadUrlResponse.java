package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ギャラリーメディア用 Presigned Upload URL 発行レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class MediaUploadUrlResponse {
    private final String uploadUrl;
    private final String fileKey;
    private final long expiresInSeconds;
}
