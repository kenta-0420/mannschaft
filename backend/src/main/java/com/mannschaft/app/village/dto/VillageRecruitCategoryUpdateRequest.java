package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 村ごと募集カテゴリの更新リクエスト（F17.1 P2 §6.2）。
 *
 * <p>部分更新（{@code null} = 変更なし）。{@code is_preset} は由来の記録のみで変更対象外
 * （プリセットも改名・削除できる。設計書 §4.2 の注）。</p>
 */
public record VillageRecruitCategoryUpdateRequest(
        @Size(max = 40) String name,
        @Size(max = 200) String description,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        Integer displayOrder
) {
}
