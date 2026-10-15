package com.mannschaft.app.social.dto;

/**
 * フレンド解除時の過去転送投稿（{@code friend_content_forwards}）の扱いを表す列挙。
 *
 * <p>
 * ADMIN がフレンド解除を実行する際、既に管理者によって転送済みの投稿をどう扱うかを
 * UI のモーダルで選択する。Phase 1 では 3 モードすべてをサポートする。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/features/F01.5_team_friend_relationships.md} §6 フレンド解除後の過去転送投稿の扱い
 * </p>
 */
public enum PastForwardHandling {

    /**
     * 過去転送投稿を保持する（デフォルト）。{@code friend_content_forwards} はそのまま残り、
     * 以降の閲覧時は UI 側で「フレンド解除済み」注釈を付ける。
     */
    KEEP,

    /**
     * 過去転送投稿を論理削除する。{@code timeline_posts.deleted_at} を設定し、
     * タイムラインから非表示にする。{@code friend_content_forwards.is_revoked = TRUE} を立てる。
     */
    SOFT_DELETE,

    /**
     * 過去転送投稿をアーカイブする。{@code timeline_posts.scope_type} を
     * {@code FRIEND_ARCHIVE} に変更し、通常タイムラインからは除外するが
     * 別エンドポイント {@code /api/v1/teams/{id}/friend-archive} で閲覧可能にする。
     */
    ARCHIVE
}
