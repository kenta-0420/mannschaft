package com.mannschaft.app.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ワークフローテンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateWorkflowTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String icon;

    @Size(max = 7)
    private final String color;

    @NotNull
    private final Boolean isSealRequired;

    private final Integer sortOrder;

    @Valid
    private final List<TemplateStepRequest> steps;

    @Valid
    private final List<TemplateFieldRequest> fields;
}
