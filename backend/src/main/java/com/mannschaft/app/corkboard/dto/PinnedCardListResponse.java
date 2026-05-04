package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * F09.8.1 Phase 3 ピン止めカード横断取得レスポンス（リスト全体）。
 *
 * <p>{@code GET /api/v1/users/me/corkboards/pinned-cards} のレスポンス本体。
 * 設計書 §4.3 と一致。</p>
 */
@Getter
@RequiredArgsConstructor
public class PinnedCardListResponse {

    /** ピン止めカード本体（pinnedAt 降順、最大 limit 件）。 */
    private final List<PinnedCardResponse> items;

    /** 次ページ取得用の不透明カーソル文字列。次ページが無い場合は null。 */
    private final String nextCursor;

    /** 当該ユーザーのピン止め済みカード総数（ページネーションに依存しない）。 */
    private final Long totalCount;
}
