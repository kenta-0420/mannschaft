package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * カレンダーエントリーレスポンスDTO。横断カレンダー表示用。
 */
@Getter
@RequiredArgsConstructor
public class CalendarEntryResponse {

    private final Long id;
    private final String title;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final Boolean allDay;
    private final String eventType;
    private final String status;
    private final String scopeType;
    private final Long scopeId;
    private final String scopeName;
    private final String myAttendanceStatus;
    /** チーム・組織のアイコン画像URL。未設定またはPERSONALスコープの場合はnull。 */
    private final String scopeIconUrl;
}
