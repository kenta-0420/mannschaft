package com.mannschaft.app.timeline;

/**
 * タイムライン投稿のスコープ種別。
 *
 * <p>
 * DB 上は {@code timeline_posts.scope_type} に {@code VARCHAR(20)} として格納される。
 * F01.5 で追加された {@link #FRIEND_TEAM} / {@link #FRIEND_FORWARD} /
 * {@link #FRIEND_ARCHIVE} は既存カラムに値を追加するのみで DDL 変更は不要
 * （V9.076 マイグレーションコメント参照）。
 * </p>
 */
public enum PostScopeType {
    /** 公開（全体） */
    PUBLIC,
    /** 組織 */
    ORGANIZATION,
    /** チーム */
    TEAM,
    /** 個人 */
    PERSONAL,
    /** F01.5 フレンドチーム管理者フィード専用（Phase 3 利用予定） */
    FRIEND_TEAM,
    /** F01.5 フレンド投稿の転送先スコープ（Phase 1 から利用） */
    FRIEND_FORWARD,
    /** F01.5 フレンド解除後のアーカイブ領域（Phase 3 利用予定） */
    FRIEND_ARCHIVE
}
