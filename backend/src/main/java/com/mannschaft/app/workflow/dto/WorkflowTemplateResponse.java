package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ワークフローテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WorkflowTemplateResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String description;
    private final String icon;
    private final String color;
    private final Boolean isSealRequired;
    private final Boolean isActive;
    private final Integer sortOrder;
    private final Long createdBy;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<TemplateStepResponse> steps;
    private final List<TemplateFieldResponse> fields;
}
