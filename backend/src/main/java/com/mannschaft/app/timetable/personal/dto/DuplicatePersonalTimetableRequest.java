package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * F03.15 個人時間割複製リクエスト（全フィールド任意。未指定はソース値を継承）。
 */
public record DuplicatePersonalTimetableRequest(
        @Size(max = 200) String name,
        @JsonProperty("academic_year") Integer academicYear,
        @JsonProperty("term_label") @Size(max = 50) String termLabel,
        @JsonProperty("effective_from") LocalDate effectiveFrom,
        @JsonProperty("effective_until") LocalDate effectiveUntil) {
}
