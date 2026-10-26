package com.mannschaft.app.skill.dto;

import java.util.List;

/**
 * スキルマトリックスレスポンス（ピボット形式）。
 */
public record SkillMatrixResponse(
        List<SkillCategoryResponse> categories,
        List<SkillMatrixRowResponse> rows
) {
}
