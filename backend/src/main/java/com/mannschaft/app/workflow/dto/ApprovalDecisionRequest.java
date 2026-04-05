package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 承認判断リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApprovalDecisionRequest {

    @NotBlank
    @Size(max = 20)
    private final String decision;

    @Size(max = 1000)
    private final String comment;

    private final Long sealId;
}
