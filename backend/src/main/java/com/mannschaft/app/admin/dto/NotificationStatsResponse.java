package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 通知配信統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class NotificationStatsResponse {

    private final Long id;
    private final LocalDate date;
    private final String channel;
    private final Integer sentCount;
    private final Integer deliveredCount;
    private final Integer failedCount;
    private final Integer bounceCount;
}
