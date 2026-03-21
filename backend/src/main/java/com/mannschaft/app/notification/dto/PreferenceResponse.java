package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PreferenceResponse {

    private final Long id;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;
    private final Boolean isEnabled;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
