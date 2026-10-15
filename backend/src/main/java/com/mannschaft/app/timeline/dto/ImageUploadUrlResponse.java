package com.mannschaft.app.timeline.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * タイムライン画像ファイル用 Presigned Upload URL 発行レスポンス。
 */
@Getter
@Builder
public class ImageUploadUrlResponse {
    /** R2 PUT 用 Presigned URL（有効期限 15分） */
    private final String uploadUrl;
    /** R2 オブジェクトキー（POST /api/v1/timeline/posts の image_file_keys に使用） */
    private final String fileKey;
    /** Presigned URL の有効期限（秒） */
    private final int expiresInSeconds;
}
