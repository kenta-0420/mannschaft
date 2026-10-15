package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ワークフロー申請ステップレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RequestStepResponse {

    private final Long id;
    private final Long requestId;
    private final Integer stepOrder;
    private final String status;
    private final LocalDateTime completedAt;
    private final LocalDateTime createdAt;
    private final List<ApproverResponse> approvers;
}
