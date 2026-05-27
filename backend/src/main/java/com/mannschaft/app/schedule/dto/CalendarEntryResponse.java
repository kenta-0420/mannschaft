package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * カレンダーエントリーレスポンスDTO。横断カレンダー表示用。
 */
@Builder(toBuilder = true)
@Getter
public class CalendarEntryResponse {

    Long               id;
    CalendarContentDto content;  // title, eventType, status
    CalendarTimeDto    time;     // startAt, endAt, allDay
    CalendarScopeDto   scope;    // scopeType, scopeId, scopeName, scopeIconUrl
    String             myAttendanceStatus;

    public record CalendarContentDto(String title, String eventType, String status) {
    }

    public record CalendarTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    /** チーム・組織のアイコン画像URL。未設定またはPERSONALスコープの場合はnull。 */
    public record CalendarScopeDto(String scopeType, Long scopeId, String scopeName,
                                   String scopeIconUrl) {
    }
}
