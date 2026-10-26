package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 身体チャートマークレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartBodyMarkResponse {

    private final Long id;
    private final String bodyPart;
    private final BigDecimal xPosition;
    private final BigDecimal yPosition;
    private final String markType;
    private final Integer severity;
    private final String note;
}
