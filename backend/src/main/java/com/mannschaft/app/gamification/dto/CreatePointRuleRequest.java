package com.mannschaft.app.gamification.dto;

import com.mannschaft.app.gamification.ActionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ポイントルール作成リクエストDTO。
 */
public record CreatePointRuleRequest(
        @NotNull ActionType actionType,
        @NotBlank @Size(max = 100) String name,
        @Positive int points,
        @Min(0) int dailyLimit
) {}
