package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BudgetForecast(
    BigDecimal dailyAvgCost,
    BigDecimal projectedMonthlyCost,
    LocalDate budgetExhaustionDate,
    BigDecimal remainingBudget,
    Integer remainingDaysAtCurrentPace,
    String pacingStatus
) {}
