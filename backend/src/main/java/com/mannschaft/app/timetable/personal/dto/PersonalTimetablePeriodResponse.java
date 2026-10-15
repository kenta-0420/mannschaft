package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;

import java.time.LocalTime;

/**
 * F03.15 Phase 2 個人時間割の時限定義レスポンス。
 */
public record PersonalTimetablePeriodResponse(
        Long id,
        @JsonProperty("period_number") Integer periodNumber,
        String label,
        @JsonProperty("start_time") LocalTime startTime,
        @JsonProperty("end_time") LocalTime endTime,
        @JsonProperty("is_break") Boolean isBreak) {

    public static PersonalTimetablePeriodResponse from(PersonalTimetablePeriodEntity entity) {
        return new PersonalTimetablePeriodResponse(
                entity.getId(),
                entity.getPeriodNumber(),
                entity.getLabel(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getIsBreak());
    }
}
