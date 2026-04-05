package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 未読通知件数レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UnreadCountResponse {

    private final long unreadCount;
}
