package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * 村内検索（F17.1 §4.12）のレスポンス DTO。
 *
 * <p>{@code GET /api/v1/villages/{villageId}/search?q=&type=&page=&size=} の戻り値。
 * POST / MESSAGE / MEMBER を横断した結果を 1 つのページとして返す。</p>
 *
 * @param items 検索結果アイテム一覧
 * @param page  ページ番号（0 始まり）
 * @param size  ページサイズ
 * @param total 全件数（ページャ用、件数概算）
 */
@Builder
public record VillageInternalSearchResponse(
        List<VillageInternalSearchItemResponse> items,
        int page,
        int size,
        long total
) {}
