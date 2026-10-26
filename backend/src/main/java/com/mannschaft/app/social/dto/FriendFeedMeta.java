package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 管理者フィード一覧のページングメタ情報 DTO（F01.5 Phase 2）。
 *
 * <p>
 * カーソルベースページングを採用。{@code hasNext = true} の場合、
 * {@code nextCursor} に次ページの起点となる投稿 ID を返す。
 * </p>
 */
@Getter
@Builder
public class FriendFeedMeta {

    /** 次ページカーソル（投稿 ID）。次ページなしの場合 null */
    private final Long nextCursor;

    /** 1 ページあたりの取得件数 */
    private final int limit;

    /** 次ページが存在するか */
    private final boolean hasNext;
}
