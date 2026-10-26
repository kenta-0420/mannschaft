package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 写真・動画レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PhotoResponse {

    private final Long id;
    private final Long albumId;
    private final String r2Key;
    private final String thumbnailR2Key;
    private final String originalFilename;
    private final String contentType;
    private final Long fileSize;
    private final Integer width;
    private final Integer height;
    private final String caption;
    private final LocalDateTime takenAt;
    private final Integer sortOrder;
    private final Long uploadedBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String mediaType;
    private final Integer durationSeconds;
    private final String videoCodec;
    private final String processingStatus;
}
