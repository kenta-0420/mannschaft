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
 * 施設予約作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateBookingRequest {

    @NotNull
    private final Long facilityId;

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

    private final List<BookingEquipmentEntry> equipment;

    /**
     * 予約備品エントリ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BookingEquipmentEntry {

        @NotNull
        private final Long equipmentId;

        @NotNull
        @Min(1)
        private final Integer quantity;
    }
}
