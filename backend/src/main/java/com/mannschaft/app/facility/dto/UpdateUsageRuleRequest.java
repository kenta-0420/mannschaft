package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * 利用ルール更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateUsageRuleRequest {

    private final BigDecimal maxHoursPerBooking;

    private final BigDecimal minHoursPerBooking;

    @Min(1)
    private final Integer maxBookingsPerMonthPerUser;

    @Min(1)
    private final Integer maxConsecutiveSlots;

    @Min(0)
    private final Integer minAdvanceHours;

    @Min(1)
    private final Integer maxAdvanceDays;

    @Min(0)
    private final Integer maxStayNights;

    private final Integer cancellationDeadlineHours;

    private final LocalTime availableTimeFrom;

    private final LocalTime availableTimeTo;

    private final List<Integer> availableDaysOfWeek;

    private final List<String> blackoutDates;

    private final String notes;
}
