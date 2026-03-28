package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * アラート発火履歴レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class AlertHistoryResponse {

    private final Long id;
    private final Long ruleId;
    private final String ruleName;
    private final String metric;
    private final LocalDateTime triggeredAt;
    private final BigDecimal metricValue;
    private final BigDecimal thresholdValue;
    private final BigDecimal comparisonValue;
    private final BigDecimal changePct;
    private final boolean notified;
}
