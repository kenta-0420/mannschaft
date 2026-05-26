package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * スケジュール詳細レスポンスDTO。一覧レスポンスに加え、詳細情報を含む。
 */
@SuperBuilder(toBuilder = true)
@Getter
public class ScheduleDetailResponse extends ScheduleResponse {

    ScheduleDetailContentDto    detail;      // description, visibility, color, commentOption
    ScheduleDetailRoleDto       roles;       // minViewRole, minResponseRole
    ScheduleDetailRecurrenceDto recurrence;  // recurrenceRule, isException, parentScheduleId
    ScheduleDetailAttendanceDto attendance;  // attendanceDeadline, myAttendance, attendanceSummary
    ScheduleDetailRelationsDto  relations;   // surveys, reminders, crossInvitations
    Long                        createdBy;

    public record ScheduleDetailContentDto(String description, String visibility, String color,
                                           String commentOption) {
    }

    public record ScheduleDetailRoleDto(String minViewRole, String minResponseRole) {
    }

    public record ScheduleDetailRecurrenceDto(RecurrenceRuleDto recurrenceRule, Boolean isException,
                                              Long parentScheduleId) {
    }

    public record ScheduleDetailAttendanceDto(LocalDateTime attendanceDeadline,
                                              AttendanceResponse myAttendance,
                                              AttendanceSummaryResponse attendanceSummary) {
    }

    public record ScheduleDetailRelationsDto(List<EventSurveyResponse> surveys,
                                             List<ReminderResponse> reminders,
                                             List<CrossRefResponse> crossInvitations) {
    }
}
