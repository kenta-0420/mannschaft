package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * ダッシュボード村フィード（F17.1 §4.13）のレスポンス DTO。
 *
 * <p>{@code GET /api/v1/me/village-feed?limit=20} の戻り値。
 * ピン留めしている村群の最新動きを横断要約として返す。</p>
 *
 * @param feed            時系列で並べた横断フィード（最新順）
 * @param pinnedVillages  ユーザーのピン村サマリー（並び順は sort_order）
 */
@Builder
public record VillageFeedResponse(
        List<VillageFeedItemResponse> feed,
        List<VillagePinnedSummaryResponse> pinnedVillages
) {}
