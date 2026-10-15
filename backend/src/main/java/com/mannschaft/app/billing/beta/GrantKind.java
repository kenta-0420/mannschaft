package com.mannschaft.app.billing.beta;

/**
 * F20.3 ベータ特典の付与種別（{@code beta_grants.grant_kind} / {@code beta_perk_criteria.grant_kind}・
 * VARCHAR(12) + CHECK）。
 *
 * <p>スコープとの整合はスキーマ CHECK（{@code chk_bg_kind_scope}）でも物理担保する（設計書 01 §1）:</p>
 * <ul>
 *   <li>{@link #INDIVIDUAL}（個人特典）: {@code scope_kind = USER} のみ。</li>
 *   <li>{@link #TEAM_ORG}（チーム・組織特典）: {@code scope_kind IN (TEAM, ORG)} のみ。</li>
 * </ul>
 */
public enum GrantKind {

    /** 個人特典（USER スコープ固定・無期限＝サービス提供期間中無償）。 */
    INDIVIDUAL,

    /** チーム・組織特典（TEAM/ORG スコープ・有期限＝下限 2 年で延長可）。 */
    TEAM_ORG
}
