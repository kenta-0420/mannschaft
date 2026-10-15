package com.mannschaft.app.gamification.dto;

import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;

/**
 * バッジレスポンスDTO。
 */
public record BadgeResponse(
        Long id,
        String name,
        BadgeType badgeType,
        BadgeConditionType conditionType,
        Integer conditionValue,
        String conditionPeriod,
        String iconEmoji,
        String iconKey,
        boolean isSystem,
        boolean isRepeatable,
        boolean isActive,
        Long version
) {}
