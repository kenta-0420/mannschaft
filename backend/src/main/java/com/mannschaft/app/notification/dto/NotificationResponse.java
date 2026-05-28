package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知レスポンスDTO（フラット構造）。
 *
 * <p>swagger.json 仕様・フロントエンドが期待するフラット構造に合わせ、
 * ネストした inner record を廃止してフィールドを直接保持する。
 */
@Builder(toBuilder = true)
@Getter
public class NotificationResponse {

    Long id;
    Long userId;

    // content（フラット化）
    String notificationType;
    String priority;
    String title;
    String body;
    String actionUrl;

    // source（フラット化）
    String sourceType;
    Long   sourceId;

    // scope（フラット化）
    String scopeType;
    Long   scopeId;
    Long   actorId;

    // status（フラット化）
    Boolean       isRead;
    LocalDateTime readAt;
    String        channelsSent;
    LocalDateTime snoozedUntil;

    LocalDateTime createdAt;
}
