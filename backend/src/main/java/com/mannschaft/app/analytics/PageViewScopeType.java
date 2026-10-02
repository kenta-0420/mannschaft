package com.mannschaft.app.analytics;

/**
 * ページビュー計測（F10.8 アクセス解析）のスコープ種別。
 *
 * <p>コードベースには単一正準の {@code ScopeType} は存在せず、ドメインごとにローカル enum が
 * 並立する慣習に従う（{@code membership.domain.ScopeType} / {@code bulletin.ScopeType} 等）。
 * 本 enum は analytics ドメインローカルとして新設し、他ドメインの enum に依存させない
 * （ドメイン境界の維持・CLAUDE.md 原則 1）。</p>
 *
 * <p>DB カラム {@code page_view_logs.scope_type} / {@code page_view_daily_stats.scope_type} は
 * {@code VARCHAR(20)} で enum 名（{@code TEAM} / {@code ORGANIZATION}）を保存する
 * （{@code bulletin_threads} V5.002 と同方式）。認可判定に渡す際は {@code name()} を
 * {@code AccessControlService.isMember(userId, scopeId, name)} へ渡す。</p>
 */
public enum PageViewScopeType {
    /** チームスコープ。 */
    TEAM,
    /** 組織スコープ。 */
    ORGANIZATION
}
