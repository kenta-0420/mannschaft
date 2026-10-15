package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * 村ごと募集カテゴリの一括並び替えリクエスト（F17.1 P2 §6.2 / AC-14）。
 *
 * <p>{@code orderedCategoryIds} の並び順どおりに {@code display_order}（10刻み）を振り直す。</p>
 */
public record VillageRecruitCategoryOrderRequest(
        @NotEmpty List<UUID> orderedCategoryIds
) {
}
