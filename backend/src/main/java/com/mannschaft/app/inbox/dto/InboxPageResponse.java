package com.mannschaft.app.inbox.dto;

import java.util.List;

/**
 * F04.11 統合通知インボックス：一覧レスポンス DTO。
 *
 * <p>複数ソース集約のため {@code totalEstimated} は概算（ハードリミット内の件数）。
 * 深いページの網羅は非保証。設計書: 02_api_design.md §3.1 / 03_business_logic.md §4。</p>
 *
 * @param items          インボックス項目一覧
 * @param page           ページ番号（0 始まり）
 * @param size           ページサイズ
 * @param totalEstimated 概算総件数（ハードリミット内）
 * @param hasMore        次ページの有無
 */
public record InboxPageResponse(
        List<InboxItemDto> items,
        int page,
        int size,
        long totalEstimated,
        boolean hasMore
) {
}
