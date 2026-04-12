package com.mannschaft.app.gallery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ギャラリーメディア（写真・動画）用 Presigned Upload URL 発行リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class MediaUploadUrlRequest {

    /** メディア種別: PHOTO / VIDEO */
    @NotBlank
    @Pattern(regexp = "PHOTO|VIDEO", message = "PHOTO または VIDEO を指定してください")
    private final String mediaType;

    /** MIME タイプ */
    @NotBlank
    private final String contentType;
}
