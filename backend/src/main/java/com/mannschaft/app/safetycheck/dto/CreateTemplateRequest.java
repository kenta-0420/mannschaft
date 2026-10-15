package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 安否確認テンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String templateName;

    @Size(max = 200)
    private final String title;

    @Size(max = 1000)
    private final String message;

    private final Integer reminderIntervalMinutes;

    private final String scopeType;

    private final Long scopeId;

    private final Integer sortOrder;
}
