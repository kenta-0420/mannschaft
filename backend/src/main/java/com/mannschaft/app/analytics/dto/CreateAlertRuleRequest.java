package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.AlertCondition;
import com.mannschaft.app.analytics.AlertMetric;
import com.mannschaft.app.analytics.ComparisonPeriod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * アラートルール作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateAlertRuleRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotNull
    private final AlertMetric metric;

    @NotNull
    private final AlertCondition condition;

    @NotNull
    private final BigDecimal threshold;

    @NotNull
    private final ComparisonPeriod comparisonPeriod;

    @NotEmpty
    private final List<String> notifyChannels;

    @Min(1)
    @Max(10)
    private final int consecutiveTriggers;

    @Min(1)
    @Max(720)
    private final int cooldownHours;
}
