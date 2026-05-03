package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F03.15 個人時間割レスポンス DTO。
 */
public record PersonalTimetableResponse(
        Long id,
        String name,
        @JsonProperty("academic_year") Integer academicYear,
        @JsonProperty("term_label") String termLabel,
        @JsonProperty("effective_from") LocalDate effectiveFrom,
        @JsonProperty("effective_until") LocalDate effectiveUntil,
        String status,
        String visibility,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("week_pattern_base_date") LocalDate weekPatternBaseDate,
        String notes,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {

    public static PersonalTimetableResponse from(PersonalTimetableEntity entity) {
        return new PersonalTimetableResponse(
                entity.getId(),
                entity.getName(),
                entity.getAcademicYear(),
                entity.getTermLabel(),
                entity.getEffectiveFrom(),
                entity.getEffectiveUntil(),
                entity.getStatus().name(),
                entity.getVisibility().name(),
                entity.getWeekPatternEnabled(),
                entity.getWeekPatternBaseDate(),
                entity.getNotes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
