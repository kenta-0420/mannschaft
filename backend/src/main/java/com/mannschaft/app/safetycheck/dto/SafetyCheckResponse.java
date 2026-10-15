package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 安否確認レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyCheckResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String title;
    private final String message;
    private final Boolean isDrill;
    private final String status;
    private final Integer reminderIntervalMinutes;
    private final Integer totalTargetCount;
    private final Long createdBy;
    private final LocalDateTime closedAt;
    private final Long closedBy;
    private final LocalDateTime createdAt;
}
