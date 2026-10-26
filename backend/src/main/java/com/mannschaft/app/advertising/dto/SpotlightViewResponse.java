package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 view 計上のレスポンス（正本 §6.3）。
 *
 * @param impressionId 採番された ad_impressions.id（重複時は既存行の id）
 * @param duplicate    600 秒以内の重複計上なら true（記録せず既存 impressionId を返し 200）
 */
public record SpotlightViewResponse(Long impressionId, boolean duplicate) {
}
