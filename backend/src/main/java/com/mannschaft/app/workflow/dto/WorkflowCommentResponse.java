package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ワークフローコメントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WorkflowCommentResponse {

    private final Long id;
    private final Long requestId;
    private final Long userId;
    private final String body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
