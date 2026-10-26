package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 逆転送履歴 View DTO。F01.5 の
 * {@code GET /api/v1/teams/{id}/friend-forward-exports} のレスポンス要素として使用する。
 *
 * <p>
 * 自チーム投稿が他フレンドチームにどのような形で転送されたかを追跡する
 * 透明性確保用 API の 1 件分。転送先チームが {@code team_friends.is_public = FALSE}
 * の場合、{@link #forwardingTeamName} は {@code "匿名チーム"} に置換される
 * （設計書 §5 透明性API 仕様）。
 * </p>
 */
@Getter
@Builder
public class FriendForwardExportView {

    /** {@code friend_content_forwards.id} */
    private final Long forwardId;

    /** 転送元投稿 ID（自チーム発信投稿） */
    private final Long sourcePostId;

    /** 転送先チーム ID（フレンドチーム側） */
    private final Long forwardingTeamId;

    /**
     * 転送先チーム名。{@code team_friends.is_public = FALSE} の場合は
     * {@code "匿名チーム"} に匿名化される。
     */
    private final String forwardingTeamName;

    /** 配信範囲 */
    private final ForwardTarget target;

    /** 管理者コメント（転送実行時に付与された任意コメント） */
    private final String comment;

    /** 転送実行日時 */
    private final LocalDateTime forwardedAt;

    /** 取消済みフラグ */
    private final Boolean isRevoked;
}
