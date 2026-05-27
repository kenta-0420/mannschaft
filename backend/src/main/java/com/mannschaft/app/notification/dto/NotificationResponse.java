package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class NotificationResponse {

    Long id;
    Long userId;

    NotificationContentDto content;
    NotificationSourceDto  source;
    NotificationScopeDto   scope;
    NotificationStatusDto  status;
    LocalDateTime          createdAt;

    public record NotificationContentDto(
            String notificationType, String priority, String title, String body, String actionUrl) {}

    public record NotificationSourceDto(String sourceType, Long sourceId) {}

    public record NotificationScopeDto(String scopeType, Long scopeId, Long actorId) {}

    public record NotificationStatusDto(
            Boolean isRead, LocalDateTime readAt, String channelsSent, LocalDateTime snoozedUntil) {}
}
