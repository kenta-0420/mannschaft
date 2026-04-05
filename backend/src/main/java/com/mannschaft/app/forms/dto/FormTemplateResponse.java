package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォームテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FormTemplateResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String description;
    private final String icon;
    private final String color;
    private final String status;
    private final Boolean requiresApproval;
    private final Long workflowTemplateId;
    private final Boolean isSealOnPdf;
    private final LocalDateTime deadline;
    private final Boolean allowEditAfterSubmit;
    private final Boolean autoFillEnabled;
    private final Integer maxSubmissionsPerUser;
    private final Integer sortOrder;
    private final Long presetId;
    private final Integer submissionCount;
    private final Integer targetCount;
    private final Long createdBy;
    private final LocalDateTime publishedAt;
    private final LocalDateTime closedAt;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<FormFieldResponse> fields;
}
