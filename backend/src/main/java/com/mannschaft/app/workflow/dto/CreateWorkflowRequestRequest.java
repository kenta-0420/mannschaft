package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ワークフロー申請作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateWorkflowRequestRequest {

    @NotNull
    private final Long templateId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String fieldValues;

    @Size(max = 30)
    private final String sourceType;

    private final Long sourceId;
}
