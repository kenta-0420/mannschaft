package com.mannschaft.app.gallery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 写真・動画アップロードリクエストDTO（Pre-signed URL 方式）。
 */
@Getter
@RequiredArgsConstructor
public class UploadPhotosRequest {

    @NotNull
    @Valid
    private final List<PhotoItem> photos;

    @Getter
    @RequiredArgsConstructor
    public static class PhotoItem {

        @NotNull
        private final String r2Key;

        @NotNull
        private final String originalFilename;

        @NotNull
        private final Long fileSize;

        private final String contentType;

        private final String caption;

        /** メディア種別: PHOTO / VIDEO。null の場合は PHOTO 扱い。 */
        private final String mediaType;

        /** VIDEO の場合のサムネイル R2 キー（クライアント生成サムネイル）。 */
        private final String thumbnailR2Key;
    }
}
