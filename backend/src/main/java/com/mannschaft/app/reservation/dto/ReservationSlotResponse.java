package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 予約スロットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReservationSlotResponse {

    private final Long id;
    private final Long teamId;
    private final Long staffUserId;
    private final String title;
    private final LocalDate slotDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Integer bookedCount;
    private final String slotStatus;
    private final String recurrenceRule;
    private final Long parentSlotId;
    private final Boolean isException;
    private final BigDecimal price;
    private final String closedReason;
    private final String note;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
