package com.mannschaft.app.skill.dto;

import com.mannschaft.app.skill.SkillStatus;

import java.time.LocalDate;

/**
 * スキルマトリックスのセルレスポンス（ユーザー × カテゴリ の交点）。
 */
public record SkillMatrixCellResponse(
        Long memberSkillId,
        SkillStatus status,
        LocalDate expiresAt
) {
}
