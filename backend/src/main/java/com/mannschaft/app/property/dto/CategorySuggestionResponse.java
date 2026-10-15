package com.mannschaft.app.property.dto;

/**
 * カテゴリ サジェスト（既存 category 値の頻度集計）レスポンス DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §4 {@code GET /property-history/categories/suggestions?since=} に対応。
 * Repository {@code aggregateCategoriesSince} の {@code Object[]} 配列（[category, count]）から
 * 写像して返却する。</p>
 */
public record CategorySuggestionResponse(
        String category,
        Long count) {
}
