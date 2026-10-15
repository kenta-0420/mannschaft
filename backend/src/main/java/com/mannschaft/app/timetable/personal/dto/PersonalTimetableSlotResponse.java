package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;

import java.math.BigDecimal;

/**
 * F03.15 Phase 2 個人時間割のコマレスポンス。
 */
public record PersonalTimetableSlotResponse(
        Long id,
        @JsonProperty("day_of_week") String dayOfWeek,
        @JsonProperty("period_number") Integer periodNumber,
        @JsonProperty("week_pattern") String weekPattern,
        @JsonProperty("subject_name") String subjectName,
        @JsonProperty("course_code") String courseCode,
        @JsonProperty("teacher_name") String teacherName,
        @JsonProperty("room_name") String roomName,
        BigDecimal credits,
        String color,
        @JsonProperty("linked_team_id") Long linkedTeamId,
        @JsonProperty("linked_timetable_id") Long linkedTimetableId,
        @JsonProperty("linked_slot_id") Long linkedSlotId,
        @JsonProperty("auto_sync_changes") Boolean autoSyncChanges,
        String notes) {

    public static PersonalTimetableSlotResponse from(PersonalTimetableSlotEntity entity) {
        return new PersonalTimetableSlotResponse(
                entity.getId(),
                entity.getDayOfWeek(),
                entity.getPeriodNumber(),
                entity.getWeekPattern() != null ? entity.getWeekPattern().name() : null,
                entity.getSubjectName(),
                entity.getCourseCode(),
                entity.getTeacherName(),
                entity.getRoomName(),
                entity.getCredits(),
                entity.getColor(),
                entity.getLinkedTeamId(),
                entity.getLinkedTimetableId(),
                entity.getLinkedSlotId(),
                entity.getAutoSyncChanges(),
                entity.getNotes());
    }
}
