package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 写真レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PhotoResponse {

    private final Long id;
    private final Long albumId;
    private final String s3Key;
    private final String thumbnailS3Key;
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
}
