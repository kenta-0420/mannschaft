package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * チーム間フォロー成功時のレスポンス DTO。
 *
 * <p>
 * {@link #isMutual} が {@code true} の場合は相互フォロー成立としてフレンド関係
 * （{@code team_friends}）も同時に生成されており、{@link #teamFriendId} ・
 * {@link #establishedAt} ・ {@link #isPublic} に値が入る。
 * </p>
 *
 * <p>
 * {@link #retryAfterSeconds} は NOWAIT 競合時に {@code 202 Accepted} で返す場合のみ
 * 設定される（通常の {@code 201 Created} では {@code null}）。
 * </p>
 */
@Getter
@Builder
public class FollowTeamResponse {

    /** 新規に生成された {@code follows.id}。NOWAIT 競合時は {@code null}。 */
    private final Long followId;

    /** フォロー元チーム ID（自チーム） */
    private final Long followerTeamId;

    /** フォロー先チーム ID */
    private final Long followedTeamId;

    /** 相互フォロー成立フラグ。{@code true} でフレンド関係成立。 */
    private final boolean mutual;

    /** フレンド関係 ID（相互成立時のみ。片方向フォロー時は {@code null}） */
    private final Long teamFriendId;

    /** フレンド関係成立日時（相互成立時のみ） */
    private final LocalDateTime establishedAt;

    /** フレンド関係の公開フラグ（相互成立時のみ） */
    private final Boolean isPublic;

    /** {@code follows} レコード作成日時（片方向・相互ともに設定） */
    private final LocalDateTime createdAt;

    /** NOWAIT 競合で {@code 202 Accepted} を返す場合の再試行秒数。通常は {@code null}。 */
    private final Integer retryAfterSeconds;
}
