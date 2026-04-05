package com.mannschaft.app.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ワークフローコメントリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class WorkflowCommentRequest {

    @NotBlank
    @Size(max = 2000)
    private final String body;
}
