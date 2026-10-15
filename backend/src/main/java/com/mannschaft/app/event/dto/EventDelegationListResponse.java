package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * イベント代理一覧レスポンス（F03.10 §4.2 GET /api/v1/events/{eventId}/delegations）。
 *
 * <p>ADMIN 向けのページネーション付き一覧。スケジュール側（§4.1）と同形式で、各要素に
 * {@code proxyVoteSessionId} / {@code proxyDelegationId} を含む。{@code reason} 表示は ADMIN のみ（§6）。</p>
 */
@Getter
@Builder
public class EventDelegationListResponse {

    /** 代理委任の一覧。 */
    private final List<EventDelegationResponse> delegations;

    /** 総件数（フィルタ後）。 */
    private final long total;

    /** 現在のページ番号（0 始まり）。 */
    private final int page;

    /** 1 ページあたりの件数。 */
    private final int size;
}
