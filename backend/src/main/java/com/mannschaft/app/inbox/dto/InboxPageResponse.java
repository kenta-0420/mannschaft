package com.mannschaft.app.inbox.dto;

import java.util.List;

/**
 * F04.11 統合通知インボックス：一覧レスポンス DTO。
 *
 * <p>複数ソース集約のため {@code totalEstimated} は概算（境界付きウィンドウ内の畳み込み後件数）。
 * Phase3 ③ の境界付きウィンドウページング（全ソース {@code Pageable}・完全全順序タイブレーク）により
 * 決定的（重複なし・load-more 連続）。MENTION・TODO_DUE・NOTIFICATION は fetch 順がグローバル順と整合し
 * 取りこぼしなし。ANNOUNCEMENT・CONFIRMABLE は取得順が priority と独立のため、稀な偏在で高 priority・
 * 低時刻の項目が後ページに送られうる（pinned/保留件数は小さく実害限定）。
 * 設計書: 02_api_design.md §3.1 / 03_business_logic.md §4.1。</p>
 *
 * @param items          インボックス項目一覧
 * @param page           ページ番号（0 始まり）
 * @param size           ページサイズ
 * @param totalEstimated 概算総件数（境界付きウィンドウ内・畳み込み後）
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
