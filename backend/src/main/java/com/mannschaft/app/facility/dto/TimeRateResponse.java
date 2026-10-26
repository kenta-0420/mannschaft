package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 時間帯別料金レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TimeRateResponse {

    private final Long id;
    private final Long facilityId;
    private final String dayType;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final BigDecimal ratePerSlot;
}
