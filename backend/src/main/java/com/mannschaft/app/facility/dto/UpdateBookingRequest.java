package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設予約更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateBookingRequest {

    @NotNull
    private final LocalDate bookingDate;

    private final LocalDate checkOutDate;

    @Min(0)
    private final Integer stayNights;

    @NotNull
    private final LocalTime timeFrom;

    @NotNull
    private final LocalTime timeTo;

    @Size(max = 500)
    private final String purpose;

    @Min(1)
    private final Integer attendeeCount;

    private final List<CreateBookingRequest.BookingEquipmentEntry> equipment;
}
