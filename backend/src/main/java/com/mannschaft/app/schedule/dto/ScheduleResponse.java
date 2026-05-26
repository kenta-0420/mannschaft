package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * スケジュール一覧用レスポンスDTO。
 */
@SuperBuilder(toBuilder = true)
@Getter
public class ScheduleResponse {

    Long id;

    ScheduleContentDto content;     // title, status, eventType, location, attendanceRequired
    ScheduleTimeDto    time;        // startAt, endAt, allDay
    ScheduleScopeDto   scope;       // scopeName, scopeIconUrl
    ScheduleAcademicDto academic;   // eventCategory, academicYear, sourceScheduleId
    ScheduleAuditDto   audit;       // createdAt, createdByDisplayName
    String             myAttendanceStatus;

    public record ScheduleContentDto(String title, String status, String eventType, String location,
                                     Boolean attendanceRequired) {
    }

    public record ScheduleTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    public record ScheduleScopeDto(String scopeName, String scopeIconUrl) {
    }

    public record ScheduleAcademicDto(EventCategoryResponse eventCategory, Integer academicYear,
                                      Long sourceScheduleId) {
    }

    public record ScheduleAuditDto(LocalDateTime createdAt, String createdByDisplayName) {
    }
}
