package com.mannschaft.app.skill.dto;

import java.util.Map;

/**
 * スキルマトリックスの行レスポンス（メンバー単位）。
 * cellsのキーはskillCategoryId。
 */
public record SkillMatrixRowResponse(
        Long userId,
        String displayName,
        Map<Long, SkillMatrixCellResponse> cells
) {
}
