package com.mannschaft.app.schedule.dto;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュール詳細レスポンスDTO。一覧レスポンスに加え、詳細情報を含む。
 */
@Getter
public class ScheduleDetailResponse extends ScheduleResponse {

    private final String description;
    private final String visibility;
    private final String minViewRole;
    private final String minResponseRole;
    private final LocalDateTime attendanceDeadline;
    private final String commentOption;
    private final RecurrenceRuleDto recurrenceRule;
    private final Boolean isException;
    private final Long parentScheduleId;
    private final String color;
    private final Long createdBy;
    private final List<EventSurveyResponse> surveys;
    private final List<ReminderResponse> reminders;
    private final AttendanceResponse myAttendance;
    private final AttendanceSummaryResponse attendanceSummary;
    private final List<CrossRefResponse> crossInvitations;

    public ScheduleDetailResponse(
            Long id,
            String title,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Boolean allDay,
            String eventType,
            String status,
            Boolean attendanceRequired,
            String location,
            LocalDateTime createdAt,
            EventCategoryResponse eventCategory,
            Integer academicYear,
            Long sourceScheduleId,
            String description,
            String visibility,
            String minViewRole,
            String minResponseRole,
            LocalDateTime attendanceDeadline,
            String commentOption,
            RecurrenceRuleDto recurrenceRule,
            Boolean isException,
            Long parentScheduleId,
            String color,
            Long createdBy,
            List<EventSurveyResponse> surveys,
            List<ReminderResponse> reminders,
            AttendanceResponse myAttendance,
            AttendanceSummaryResponse attendanceSummary,
            List<CrossRefResponse> crossInvitations) {
        super(id, title, startAt, endAt, allDay, eventType, status,
                attendanceRequired, location, createdAt, eventCategory, academicYear, sourceScheduleId,
                null, null, null);
        this.description = description;
        this.visibility = visibility;
        this.minViewRole = minViewRole;
        this.minResponseRole = minResponseRole;
        this.attendanceDeadline = attendanceDeadline;
        this.commentOption = commentOption;
        this.recurrenceRule = recurrenceRule;
        this.isException = isException;
        this.parentScheduleId = parentScheduleId;
        this.color = color;
        this.createdBy = createdBy;
        this.surveys = surveys;
        this.reminders = reminders;
        this.myAttendance = myAttendance;
        this.attendanceSummary = attendanceSummary;
        this.crossInvitations = crossInvitations;
    }
}
