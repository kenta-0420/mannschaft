package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン画像ファイル用 Presigned Upload URL 発行リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ImageUploadUrlRequest {

    /** MIME タイプ（image/jpeg, image/png, image/webp 等） */
    @NotBlank
    private final String contentType;

    /** アップロード先スコープ種別: TEAM / ORGANIZATION / PUBLIC / PERSONAL */
    @NotBlank
    private final String scopeType;

    /** スコープ ID ("0" or Long 文字列) */
    private final Long scopeId;
}
