package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ワークフロー添付ファイルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WorkflowAttachmentResponse {

    private final Long id;
    private final Long requestId;
    private final String fileKey;
    private final String originalFilename;
    private final Long fileSize;
    private final Long uploadedBy;
    private final LocalDateTime createdAt;
}
