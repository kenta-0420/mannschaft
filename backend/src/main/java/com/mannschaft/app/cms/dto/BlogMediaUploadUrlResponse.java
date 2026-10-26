package com.mannschaft.app.cms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * ブログメディアアップロード URL 発行レスポンス DTO。
 * IMAGE の場合は uploadUrl / expiresIn が設定され、uploadId / partSize は null。
 * VIDEO の場合は uploadId / partSize が設定され、uploadUrl / expiresIn は null。
 */
@Getter
@Builder
public class BlogMediaUploadUrlResponse {

    /** blog_media_uploads.id（クライアントがメディア ID を保持し、記事保存時に渡す） */
    @JsonProperty("media_id")
    private final Long mediaId;

    /** メディア種別（"IMAGE" または "VIDEO"） */
    @JsonProperty("media_type")
    private final String mediaType;

    /** R2 オブジェクトキー */
    @JsonProperty("file_key")
    private final String fileKey;

    // === IMAGE のみ設定（VIDEO は null） ===

    /** Presigned PUT URL（IMAGE のみ） */
    @JsonProperty("upload_url")
    private final String uploadUrl;

    /** Presigned URL の有効秒数（IMAGE のみ、600秒） */
    @JsonProperty("expires_in")
    private final Integer expiresIn;

    // === VIDEO のみ設定（IMAGE は null） ===

    /** Multipart Upload ID（VIDEO のみ。クライアントが /files/multipart/{uploadId}/part-url で利用） */
    @JsonProperty("upload_id")
    private final String uploadId;

    /** 推奨パートサイズ（VIDEO のみ、10MB = 10_485_760） */
    @JsonProperty("part_size")
    private final Long partSize;
}
