package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 安否確認作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSafetyCheckRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 1000)
    private final String message;

    @NotNull
    private final String scopeType;

    @NotNull
    private final Long scopeId;

    private final Boolean isDrill;

    private final Integer reminderIntervalMinutes;

    private final Long templateId;
}
