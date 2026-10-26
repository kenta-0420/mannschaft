package com.mannschaft.app.chat.dto;

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
    private final Long messageId;
    private final String fileKey;
    private final String fileName;
    private final Long fileSize;
    private final String contentType;
    private final LocalDateTime createdAt;
}
