package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 薬剤レシピレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartFormulaResponse {

    private final Long id;
    private final String productName;
    private final String ratio;
    private final Integer processingTimeMinutes;
    private final String temperature;
    private final LocalDate patchTestDate;
    private final String patchTestResult;
    private final String note;
    private final Integer sortOrder;
}
