package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 繰り返しルールDTO。スケジュールの繰り返し設定を表現する。
 */
public record RecurrenceRuleDto(
        @NotNull String type,
        @Min(1) @Max(99) int interval,
        List<String> daysOfWeek,
        @NotNull String endType,
        LocalDate endDate,
        @Min(1) @Max(365) Integer count
) {}
