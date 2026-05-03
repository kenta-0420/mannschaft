package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * F03.15 個人時間割更新リクエスト（PATCH 用・全フィールド任意）。
 */
public record UpdatePersonalTimetableRequest(
        @Size(max = 200) String name,
        @JsonProperty("academic_year") Integer academicYear,
        @JsonProperty("term_label") @Size(max = 50) String termLabel,
        @JsonProperty("effective_from") LocalDate effectiveFrom,
        @JsonProperty("effective_until") LocalDate effectiveUntil,
        @Size(max = 20) String visibility,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("week_pattern_base_date") LocalDate weekPatternBaseDate,
        @Size(max = 500) String notes) {
}
