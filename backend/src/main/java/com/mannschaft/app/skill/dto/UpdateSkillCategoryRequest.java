package com.mannschaft.app.skill.dto;

/**
 * スキルカテゴリ更新リクエスト。
 */
public record UpdateSkillCategoryRequest(
        String name,
        String description,
        String icon,
        Integer sortOrder,
        Boolean isActive,
        Long version
) {
}
