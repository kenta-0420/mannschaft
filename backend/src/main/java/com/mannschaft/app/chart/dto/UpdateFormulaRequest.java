package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 薬剤レシピ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFormulaRequest {

    @NotBlank
    @Size(max = 200)
    private final String productName;

    @Size(max = 100)
    private final String ratio;

    private final Integer processingTimeMinutes;

    @Size(max = 50)
    private final String temperature;

    private final LocalDate patchTestDate;

    @Size(max = 20)
    private final String patchTestResult;

    @Size(max = 500)
    private final String note;

    private final Integer sortOrder;
}
