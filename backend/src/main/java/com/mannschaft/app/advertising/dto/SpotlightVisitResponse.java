package com.mannschaft.app.advertising.dto;

/**
 * F09.19.2 visit 計上のレスポンス（正本 §6.4）。
 *
 * @param clickId 採番された ad_clicks.id（クールダウン中の再 visit は null で 200）
 */
public record SpotlightVisitResponse(Long clickId) {
}
