package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * アラートルールレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class AlertRuleResponse {

    private final Long id;
    private final String name;
    private final String metric;
    private final String condition;
    private final BigDecimal threshold;
    private final String comparisonPeriod;
    private final boolean enabled;
    private final List<String> notifyChannels;
    private final int consecutiveTriggers;
    private final int cooldownHours;
    private final LocalDateTime lastTriggeredAt;
    private final LocalDateTime createdAt;
}
