package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * フレンドチーム一覧の 1 エントリを表す View DTO。
 *
 * <p>
 * {@code GET /api/v1/teams/{id}/friends} のレスポンスで使用する。
 * 設計書 §5 では {@code friend_team} ネスト構造だが、Phase 1 段階では
 * 相手チームの基本情報（ID・名称）をフラットに持ち、次陣で {@code FriendTeamProfileResolver}
 * を介して拡張属性（アイコン・都道府県等）を埋め込む想定とする。
 * </p>
 */
@Getter
@Builder
public class TeamFriendView {

    /** {@code team_friends.id} */
    private final Long teamFriendId;

    /** 相手チーム ID（閲覧者視点から見てフレンドになっている側） */
    private final Long friendTeamId;

    /** 相手チーム名（非公開の場合は空文字が入り得るため Nullable 扱い） */
    private final String friendTeamName;

    /** フレンド関係の公開フラグ */
    private final boolean isPublic;

    /** フレンド関係成立日時 */
    private final LocalDateTime establishedAt;
}
