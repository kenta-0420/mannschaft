package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * F03.15 Phase 2 個人時間割の時限定義リクエスト要素。
 *
 * <p>{@code PUT /periods} の {@link BulkPersonalTimetablePeriodRequest} 内で使用される。</p>
 */
public record PersonalTimetablePeriodRequest(
        @JsonProperty("period_number")
        @NotNull
        @Min(1)
        @Max(15)
        Integer periodNumber,

        @NotBlank
        @Size(max = 50)
        String label,

        @JsonProperty("start_time")
        @NotNull
        LocalTime startTime,

        @JsonProperty("end_time")
        @NotNull
        LocalTime endTime,

        @JsonProperty("is_break")
        Boolean isBreak) {
}
