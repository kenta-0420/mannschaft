package com.mannschaft.app.gamification.dto;

/**
 * ポイントサマリーレスポンスDTO。
 */
public record PointSummaryResponse(
        int totalPoints,
        int weeklyPoints,
        int monthlyPoints,
        int yearlyPoints
) {}
