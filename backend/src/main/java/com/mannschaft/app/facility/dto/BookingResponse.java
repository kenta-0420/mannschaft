package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 施設予約一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BookingResponse {

    private final Long id;
    private final Long facilityId;
    private final String facilityName;
    private final Long bookedBy;
    private final LocalDate bookingDate;
    private final LocalDate checkOutDate;
    private final Integer stayNights;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final Integer slotCount;
    private final String purpose;
    private final BigDecimal totalFee;
    private final String status;
    private final LocalDateTime createdAt;
}
