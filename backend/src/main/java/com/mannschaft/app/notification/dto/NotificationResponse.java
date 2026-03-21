package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class NotificationResponse {

    private final Long id;
    private final Long userId;
    private final String notificationType;
    private final String priority;
    private final String title;
    private final String body;
    private final String sourceType;
    private final Long sourceId;
    private final String scopeType;
    private final Long scopeId;
    private final String actionUrl;
    private final Long actorId;
    private final Boolean isRead;
    private final LocalDateTime readAt;
    private final String channelsSent;
    private final LocalDateTime snoozedUntil;
    private final LocalDateTime createdAt;
}
