package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン動画ファイル用 Presigned Upload URL 発行レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class VideoUploadUrlResponse {
    /** R2 PUT 用 Presigned URL（有効期限 15分） */
    private final String uploadUrl;
    /** R2 オブジェクトキー（POST /api/v1/timeline/posts の video_file_keys に使用） */
    private final String fileKey;
    /** Presigned URL の有効期限（秒） */
    private final long expiresInSeconds;
}
