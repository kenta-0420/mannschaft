package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン動画ファイル用 Presigned Upload URL 発行リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class VideoUploadUrlRequest {

    /** MIME タイプ。動画ファイルのみ許可 */
    @NotBlank
    @Pattern(regexp = "video/(mp4|webm|quicktime)",
             message = "対応形式: video/mp4, video/webm, video/quicktime")
    private final String contentType;

    /** アップロード先スコープ種別: TEAM / ORGANIZATION / PUBLIC */
    @NotBlank
    private final String scopeType;

    /** スコープ ID (PUBLIC の場合は 0) */
    private final Long scopeId;
}
