package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ワークフロー申請更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateWorkflowRequestRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String fieldValues;

    @NotNull
    private final Long version;
}
