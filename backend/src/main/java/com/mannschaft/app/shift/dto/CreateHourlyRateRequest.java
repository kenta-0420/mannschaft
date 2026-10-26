package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 時給設定作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateHourlyRateRequest {

    @NotNull
    private final Long userId;

    @NotNull
    @Positive
    private final BigDecimal hourlyRate;

    @NotNull
    private final LocalDate effectiveFrom;
}
