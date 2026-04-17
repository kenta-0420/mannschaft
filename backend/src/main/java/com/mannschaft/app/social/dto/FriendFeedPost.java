package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 管理者フィードの個別投稿 DTO（F01.5 Phase 2）。
 *
 * <p>
 * {@code GET /api/v1/teams/{id}/friend-feed} のレスポンスに含まれる
 * 各フレンドチーム投稿を表す。発信元チーム・投稿内容・転送状況を保持する。
 * </p>
 */
@Getter
@Builder
public class FriendFeedPost {

    /** 投稿 ID（timeline_posts.id） */
    private final Long postId;

    /** 発信元フレンドチーム情報 */
    private final FriendFeedSourceTeam sourceTeam;

    /** 投稿本文 */
    private final String content;

    /** フィードへの受信日時 ISO8601 文字列（投稿の作成日時） */
    private final String receivedAt;

    /** 自チームの転送状況 */
    private final FriendFeedForwardStatus forwardStatus;
}
