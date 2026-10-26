package com.mannschaft.app.gamification.dto;

/**
 * ゲーミフィケーション設定レスポンスDTO。
 */
public record GamificationConfigResponse(
        Long id,
        String scopeType,
        Long scopeId,
        boolean isEnabled,
        boolean isRankingEnabled,
        int rankingDisplayCount,
        Integer pointResetMonth,
        Long version
) {}
