package com.mannschaft.app.gamification.dto;

import com.mannschaft.app.gamification.ActionType;

/**
 * ポイントルールレスポンスDTO。
 */
public record PointRuleResponse(
        Long id,
        ActionType actionType,
        String name,
        int points,
        int dailyLimit,
        boolean isSystem,
        boolean isActive,
        Long version
) {}
