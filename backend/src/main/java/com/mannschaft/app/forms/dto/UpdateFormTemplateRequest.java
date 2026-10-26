package com.mannschaft.app.forms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォームテンプレート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFormTemplateRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String icon;

    @Size(max = 7)
    private final String color;

    private final Boolean requiresApproval;

    private final Long workflowTemplateId;

    private final Boolean isSealOnPdf;

    private final LocalDateTime deadline;

    private final Boolean allowEditAfterSubmit;

    private final Boolean autoFillEnabled;

    private final Integer maxSubmissionsPerUser;

    private final Integer sortOrder;

    private final Integer targetCount;

    @Valid
    private final List<FormFieldRequest> fields;
}
