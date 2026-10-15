package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メッセージプリセットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyPresetResponse {

    private final Long id;
    private final String body;
    private final Integer sortOrder;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
}
