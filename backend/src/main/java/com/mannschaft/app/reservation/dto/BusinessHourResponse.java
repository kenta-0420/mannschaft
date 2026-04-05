package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * 営業時間レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BusinessHourResponse {

    private final Long id;
    private final Long teamId;
    private final String dayOfWeek;
    private final Boolean isOpen;
    private final LocalTime openTime;
    private final LocalTime closeTime;
}
