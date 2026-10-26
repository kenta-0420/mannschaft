package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回覧添付ファイルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AttachmentResponse {

    private final Long id;
    private final Long documentId;
    private final String fileKey;
    private final String originalFilename;
    private final Long fileSize;
    private final String mimeType;
    private final LocalDateTime createdAt;
}
