package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 添付ファイルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AttachmentResponse {

    private final Long id;
    private final String targetType;
    private final Long targetId;
    private final String fileKey;
    private final String originalFilename;
    private final Long fileSize;
    private final String contentType;
    private final Long createdBy;
    private final LocalDateTime createdAt;
}
