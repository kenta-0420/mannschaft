package com.mannschaft.app.advertising.campaign.service.moderation;

import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;

/**
 * F09.17 Phase 11-b 自動 NG 検知ヒット 1 件分。
 *
 * @param word     検出された NG ワード
 * @param category {@code ad_ng_words.category} カテゴリ (PHARMA / SUPERLATIVE / ...)
 * @param severity 検出ワードの重大度 (WARN / BLOCK)
 */
public record DetectedNgWord(
        String word,
        String category,
        AdNgWordSeverity severity
) {
}
