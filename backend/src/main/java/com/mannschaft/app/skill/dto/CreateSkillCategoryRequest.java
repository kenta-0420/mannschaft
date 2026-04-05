package com.mannschaft.app.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * スキルカテゴリ作成リクエスト。
 */
public record CreateSkillCategoryRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description,

        String icon,

        Integer sortOrder
) {
}
