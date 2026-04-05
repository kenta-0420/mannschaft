package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 居住者書類レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ResidentDocumentResponse {

    private final Long id;
    private final Long residentId;
    private final String documentType;
    private final String fileName;
    private final String s3Key;
    private final Integer fileSize;
    private final String contentType;
    private final Long uploadedBy;
    private final LocalDateTime createdAt;
}
