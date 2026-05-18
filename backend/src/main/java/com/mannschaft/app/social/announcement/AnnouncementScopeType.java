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
 *   <li>{@link #COMMITTEE} — 委員会スコープ（committees.id を scope_id として参照）</li>
 *   <li>{@link #ADVERTISER_AD} — 広告主キャンペーン（F09.17、scope_id は advertiser_accounts.id）</li>
 * </ul>
 */
public enum AnnouncementScopeType {

    /** チームスコープ */
    TEAM,

    /** 組織スコープ */
    ORGANIZATION,

    /** 委員会スコープ */
    COMMITTEE,

    /**
     * 広告主キャンペーンスコープ（F09.17 Phase 11-b ε-B）。
     * {@code scope_id} は {@code advertiser_accounts.id} を表す。
     * 広告は組織横断で配信されるため TEAM/ORGANIZATION では表現できない独立スコープ。
     */
    ADVERTISER_AD
}
