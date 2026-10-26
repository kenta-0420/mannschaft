package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フィードバック回答リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FeedbackRespondRequest {

    @NotBlank
    private final String adminResponse;

    private final Boolean isPublicResponse;
}
