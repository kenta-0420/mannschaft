package com.mannschaft.app.gamification.dto;

/**
 * ランキングレスポンスDTO。
 */
public record RankingResponse(
        Long userId,
        String displayName,
        int totalPoints,
        int rankPosition
) {}
