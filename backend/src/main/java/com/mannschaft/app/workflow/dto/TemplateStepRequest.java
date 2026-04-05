package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートステップ定義リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateStepRequest {

    @NotNull
    private final Integer stepOrder;

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    @Size(max = 10)
    private final String approvalType;

    @NotBlank
    @Size(max = 10)
    private final String approverType;

    private final String approverUserIds;

    @Size(max = 30)
    private final String approverRole;

    private final Short autoApproveDays;
}
