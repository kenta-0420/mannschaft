package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 利用ルールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UsageRuleResponse {

    private final Long id;
    private final Long facilityId;
    private final BigDecimal maxHoursPerBooking;
    private final BigDecimal minHoursPerBooking;
    private final Integer maxBookingsPerMonthPerUser;
    private final Integer maxConsecutiveSlots;
    private final Integer minAdvanceHours;
    private final Integer maxAdvanceDays;
    private final Integer maxStayNights;
    private final Integer cancellationDeadlineHours;
    private final LocalTime availableTimeFrom;
    private final LocalTime availableTimeTo;
    private final String availableDaysOfWeek;
    private final String blackoutDates;
    private final String notes;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
