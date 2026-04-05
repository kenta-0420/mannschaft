package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カレンダー同期ON/OFFレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CalendarSyncToggleResponse {

    private final String scopeType;
    private final Long scopeId;
    private final boolean isEnabled;
    private final int backfillCount;
}
