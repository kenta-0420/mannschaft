package com.mannschaft.app.skill.dto;

import java.time.LocalDateTime;

/**
 * スキルカテゴリレスポンス。
 */
public record SkillCategoryResponse(
        Long id,
        String name,
        String description,
        String icon,
        int sortOrder,
        boolean isActive,
        LocalDateTime createdAt
) {
}
