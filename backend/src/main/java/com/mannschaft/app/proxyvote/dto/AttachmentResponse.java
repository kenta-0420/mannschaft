package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 添付ファイルレスポンスDTO。
 */
@Getter
@Builder
public class AttachmentResponse {

    private final Long id;
    private final String targetType;
    private final String originalFilename;
    private final Integer fileSize;
    private final String mimeType;
    private final String attachmentType;
    private final Long uploadedBy;
    private final LocalDateTime createdAt;
}
