package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

/** F03.16 スレッド開閉レスポンス（設計書 §4.4）。 */
@Getter
@Builder
public class ThreadSettingsResponse {
    private final Long scheduleId;
    private final boolean commentsEnabled;
}
