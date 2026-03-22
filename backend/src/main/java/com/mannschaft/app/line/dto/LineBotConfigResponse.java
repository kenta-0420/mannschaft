package com.mannschaft.app.line.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * LINE BOT設定レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class LineBotConfigResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String channelId;
    private final String webhookSecret;
    private final String botUserId;
    private final Boolean isActive;
    private final Boolean notificationEnabled;
    private final Long configuredBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
