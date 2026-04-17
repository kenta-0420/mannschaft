package com.mannschaft.app.social.announcement;

/**
 * お知らせウィジェットの表示スコープ種別。
 *
 * <p>
 * お知らせフィードが属するスコープ（チームまたは組織）を識別する。
 * 既存の {@code ScopeType} と競合しないよう、このパッケージ専用の enum として定義する。
 * </p>
 *
 * <ul>
 *   <li>{@link #TEAM} — チームスコープ（teams.id を scope_id として参照）</li>
 *   <li>{@link #ORGANIZATION} — 組織スコープ（organizations.id を scope_id として参照）</li>
 * </ul>
 */
public enum AnnouncementScopeType {

    /** チームスコープ */
    TEAM,

    /** 組織スコープ */
    ORGANIZATION
}
