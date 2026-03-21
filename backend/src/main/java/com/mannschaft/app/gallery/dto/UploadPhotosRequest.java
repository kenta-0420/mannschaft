package com.mannschaft.app.gallery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 写真アップロードリクエストDTO（Pre-signed URL 方式）。
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
        private final String s3Key;

        @NotNull
        private final String originalFilename;

        @NotNull
        private final Integer fileSize;

        private final String contentType;

        private final String caption;
    }
}
