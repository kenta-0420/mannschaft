package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 安否確認テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyTemplateResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String templateName;
    private final String title;
    private final String message;
    private final Integer reminderIntervalMinutes;
    private final Boolean isSystemDefault;
    private final Integer sortOrder;
    private final Long createdBy;
    private final LocalDateTime createdAt;
}
