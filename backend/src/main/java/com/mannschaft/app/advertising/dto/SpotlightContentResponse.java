package com.mannschaft.app.advertising.dto;

import java.util.List;

/**
 * F09.19.2 {@code GET /api/v1/spotlight/content} のレスポンス（正本 §6.2）。
 *
 * <p>{@code data.items} 以外のトップレベルフィールドは持たない。items の長さは 0〜count。
 * 有料プランゲート該当・候補ゼロ・FEATURE_V9 無効はいずれも {@code items: []}（200 固定）で返す。</p>
 *
 * @param items 掲載面が表示すべき候補（長さ 0〜count）
 */
public record SpotlightContentResponse(List<SpotlightItem> items) {
}
