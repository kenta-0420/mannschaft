package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 承認者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApproverResponse {

    private final Long id;
    private final Long requestStepId;
    private final Long approverUserId;
    private final String decision;
    private final LocalDateTime decisionAt;
    private final String decisionComment;
    private final Long sealId;
    private final LocalDateTime createdAt;
}
