package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * F03.15 個人時間割作成リクエスト。
 */
public record CreatePersonalTimetableRequest(
        @NotBlank @Size(max = 200) String name,
        @JsonProperty("academic_year") Integer academicYear,
        @JsonProperty("term_label") @Size(max = 50) String termLabel,
        @JsonProperty("effective_from") @NotNull LocalDate effectiveFrom,
        @JsonProperty("effective_until") LocalDate effectiveUntil,
        @Size(max = 20) String visibility,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("week_pattern_base_date") LocalDate weekPatternBaseDate,
        @Size(max = 500) String notes,
        @JsonProperty("init_period_template") @Size(max = 20) String initPeriodTemplate) {
}
