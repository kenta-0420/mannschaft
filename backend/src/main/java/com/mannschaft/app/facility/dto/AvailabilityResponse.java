package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 空き状況レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AvailabilityResponse {

    private final Long facilityId;
    private final LocalDate date;
    private final List<AvailabilitySlot> slots;

    /**
     * 空き状況スロット。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AvailabilitySlot {
        private final LocalTime timeFrom;
        private final LocalTime timeTo;
        private final Boolean available;
        private final BigDecimal ratePerSlot;
    }
}
