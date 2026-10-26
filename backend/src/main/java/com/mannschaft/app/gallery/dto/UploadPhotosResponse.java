package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 写真アップロードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UploadPhotosResponse {

    private final int uploadedCount;
    private final int albumPhotoCount;
    private final List<UploadedPhotoInfo> photos;

    @Getter
    @RequiredArgsConstructor
    public static class UploadedPhotoInfo {
        private final Long id;
        private final String thumbnailUrl;
        private final String processingStatus;
    }
}
