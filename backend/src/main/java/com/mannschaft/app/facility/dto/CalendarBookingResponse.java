package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * カレンダー予約レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CalendarBookingResponse {

    private final Long id;
    private final Long facilityId;
    private final String facilityName;
    private final LocalDate bookingDate;
    private final LocalDate checkOutDate;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final String status;
    private final Long bookedBy;
}
