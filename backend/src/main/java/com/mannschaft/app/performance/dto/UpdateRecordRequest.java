package com.mannschaft.app.performance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * パフォーマンス記録更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateRecordRequest {

    @NotNull
    private final BigDecimal value;

    @Size(max = 300)
    private final String note;

    @NotNull
    private final LocalDate recordedDate;
}
