package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 時間帯別料金エントリDTO。
 */
@Getter
@RequiredArgsConstructor
public class TimeRateEntry {

    @NotNull
    private final String dayType;

    @NotNull
    private final LocalTime timeFrom;

    @NotNull
    private final LocalTime timeTo;

    @NotNull
    private final BigDecimal ratePerSlot;
}
