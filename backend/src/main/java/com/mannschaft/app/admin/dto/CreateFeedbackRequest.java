package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フィードバック作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFeedbackRequest {

    @NotBlank
    private final String scopeType;

    private final Long scopeId;

    @NotBlank
    private final String category;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    private final Boolean isAnonymous;
}
