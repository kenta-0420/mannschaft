package com.mannschaft.app.cms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブログメディアアップロード URL 発行リクエスト DTO。
 * 画像（IMAGE）と動画（VIDEO）の両方に対応する。
 */
@Getter
@RequiredArgsConstructor
public class BlogMediaUploadUrlRequest {

    /** メディア種別（"IMAGE" または "VIDEO"） */
    @NotBlank
    @JsonProperty("media_type")
    private final String mediaType;

    /** ファイルの MIME タイプ（例: "image/jpeg", "video/mp4"） */
    @NotBlank
    @JsonProperty("content_type")
    private final String contentType;

    /** ファイルサイズ（バイト） */
    @Positive
    @JsonProperty("file_size")
    private final long fileSize;

    /** スコープ種別（"TEAM", "ORGANIZATION", "PERSONAL"） */
    @NotBlank
    @JsonProperty("scope_type")
    private final String scopeType;

    /** スコープ ID */
    @NotNull
    @JsonProperty("scope_id")
    private final Long scopeId;

    /**
     * 記事 ID（記事編集中に渡す。新規作成中は null）。
     * この値が設定されている場合、記事あたりのメディア枚数・本数上限を検証する。
     */
    @JsonProperty("blog_post_id")
    private final Long blogPostId;
}
