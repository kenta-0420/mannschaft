package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * スケジュール代理一覧レスポンス（F03.10 §4.1 GET /api/v1/schedules/{scheduleId}/delegations）。
 *
 * <p>ADMIN 向けのページネーション付き一覧。{@code total} は当該スケジュールの代理委任総件数、
 * {@code page} / {@code size} はリクエストの反映値（§4.1）。各要素の {@code reason} 表示は
 * ADMIN のみに限定する（§6）。</p>
 */
@Getter
@Builder
public class ScheduleDelegationListResponse {

    /** 代理委任の一覧。 */
    private final List<ScheduleDelegationResponse> delegations;

    /** 総件数（フィルタ後）。 */
    private final long total;

    /** 現在のページ番号（0 始まり）。 */
    private final int page;

    /** 1 ページあたりの件数。 */
    private final int size;
}
