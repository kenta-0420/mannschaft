package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 時給設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class HourlyRateResponse {

    private final Long id;
    private final Long userId;
    private final Long teamId;
    private final BigDecimal hourlyRate;
    private final LocalDate effectiveFrom;
    private final LocalDateTime createdAt;
}
