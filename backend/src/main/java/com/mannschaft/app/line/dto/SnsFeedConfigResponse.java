package com.mannschaft.app.line.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * SNSフィード設定レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class SnsFeedConfigResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String provider;
    private final String accountUsername;
    private final Short displayCount;
    private final Boolean isActive;
    private final Long configuredBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
