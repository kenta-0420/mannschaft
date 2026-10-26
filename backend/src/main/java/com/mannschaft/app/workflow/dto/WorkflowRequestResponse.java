package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ワークフロー申請レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WorkflowRequestResponse {

    private final Long id;
    private final Long templateId;
    private final String scopeType;
    private final Long scopeId;
    private final String title;
    private final String status;
    private final Long requestedBy;
    private final LocalDateTime requestedAt;
    private final Integer currentStepOrder;
    private final String fieldValues;
    private final Long version;
    private final String sourceType;
    private final Long sourceId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<RequestStepResponse> steps;
}
