package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 管理者フィード一覧レスポンス DTO（F01.5 Phase 2）。
 *
 * <p>
 * {@code GET /api/v1/teams/{id}/friend-feed} のトップレベルレスポンス。
 * フレンドチームの {@code share_with_friends = TRUE} 投稿一覧と
 * カーソルページング情報を保持する。
 * </p>
 */
@Getter
@Builder
public class FriendFeedResponse {

    /** フィード投稿一覧 */
    private final List<FriendFeedPost> data;

    /** ページングメタ情報 */
    private final FriendFeedMeta meta;
}
