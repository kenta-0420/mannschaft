package com.mannschaft.app.gamification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * ゲーミフィケーション設定更新リクエストDTO。
 */
public record UpdateGamificationConfigRequest(
        boolean isEnabled,
        boolean isRankingEnabled,
        @Min(1) @Max(100) int rankingDisplayCount,
        @Min(1) @Max(12) Integer pointResetMonth,
        @NotNull Long version
) {}
