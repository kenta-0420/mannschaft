package com.mannschaft.app.gamification.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * ポイントルール更新リクエストDTO。
 */
public record UpdatePointRuleRequest(
        @Positive Integer points,
        @Min(0) Integer dailyLimit,
        Boolean isActive,
        @NotNull Long version
) {}
