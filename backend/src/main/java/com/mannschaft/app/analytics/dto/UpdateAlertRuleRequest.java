package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.AlertCondition;
import com.mannschaft.app.analytics.AlertMetric;
import com.mannschaft.app.analytics.ComparisonPeriod;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * アラートルール更新リクエスト（部分更新）。
 */
@Getter
@RequiredArgsConstructor
public class UpdateAlertRuleRequest {

    private final String name;
    private final AlertMetric metric;
    private final AlertCondition condition;
    private final BigDecimal threshold;
    private final ComparisonPeriod comparisonPeriod;
    private final Boolean enabled;
    private final List<String> notifyChannels;
    private final Integer consecutiveTriggers;
    private final Integer cooldownHours;
}
