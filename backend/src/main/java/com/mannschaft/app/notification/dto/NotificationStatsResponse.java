package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知統計レスポンスDTO。管理者向け。
 */
@Getter
@RequiredArgsConstructor
public class NotificationStatsResponse {

    private final long totalNotifications;
    private final long unreadCount;
    private final long readCount;
    private final long totalSubscriptions;
}
