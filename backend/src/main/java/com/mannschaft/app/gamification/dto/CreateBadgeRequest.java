package com.mannschaft.app.gamification.dto;

import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * バッジ作成リクエストDTO。
 */
public record CreateBadgeRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull BadgeType badgeType,
        @NotNull BadgeConditionType conditionType,
        Integer conditionValue,
        String conditionPeriod,
        @Size(max = 10) String iconEmoji,
        boolean isRepeatable
) {}
