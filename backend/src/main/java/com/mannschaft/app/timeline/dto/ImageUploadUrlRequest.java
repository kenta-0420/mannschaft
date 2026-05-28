package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン画像ファイル用 Presigned Upload URL 発行リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ImageUploadUrlRequest {

    /** MIME タイプ。画像ファイルのみ許可 */
    @NotBlank
    @Pattern(regexp = "image/(jpeg|png|gif|webp|heic)",
             message = "対応形式: image/jpeg, image/png, image/gif, image/webp, image/heic")
    private final String contentType;

    /** アップロード先スコープ種別: TEAM / ORGANIZATION / PUBLIC */
    @NotBlank
    private final String scopeType;

    /** スコープ ID (PUBLIC の場合は 0) */
    private final Long scopeId;
}
