package com.mannschaft.app.performance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * パフォーマンス記録入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateRecordRequest {

    @NotNull
    private final Long metricId;

    @NotNull
    private final Long userId;

    @NotNull
    private final LocalDate recordedDate;

    @NotNull
    private final BigDecimal value;

    @Size(max = 300)
    private final String note;

    private final Long scheduleId;
}
