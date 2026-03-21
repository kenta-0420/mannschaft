package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 身体チャートマークリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartBodyMarkRequest {

    @NotBlank
    @Size(max = 20)
    private final String bodyPart;

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private final BigDecimal xPosition;

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private final BigDecimal yPosition;

    @NotBlank
    @Size(max = 20)
    private final String markType;

    @NotNull
    @Min(1)
    @Max(5)
    private final Integer severity;

    @Size(max = 300)
    private final String note;
}
