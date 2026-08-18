package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;

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
    String             targetMode;
    Integer            targetCount;
    List<ScheduleTargetResponse.TargetMember> targets;

    /**
     * リマインダー一覧（機能55 第三陣）。詳細 GET のみ populate し、一覧 GET では null。
     * 一覧で件数が膨らむのを避けるため、あえて詳細応答に限定して載せる。
     */
    List<ReminderResponse> reminders;

    /**
     * 予約タスク一覧（機能55 第三陣）。PENDING を含む全状態。詳細 GET のみ populate し、一覧 GET では null。
     */
    List<ScheduledTaskResponse> scheduledTasks;

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
