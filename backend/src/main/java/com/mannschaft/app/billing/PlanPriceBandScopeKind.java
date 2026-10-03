package com.mannschaft.app.billing;

/**
 * F20.1: {@code plan_price_bands.scope_kind} の区分（VARCHAR(8) + CHECK）。
 *
 * <p>USER スコープは {@code plans.base_monthly_price_jpy} を使いバンドを持たないため、
 * {@link EntitlementScopeKind} とは別に TEAM/ORG のみの専用 enum を用意する
 * （設計書 01 §2.4）。</p>
 */
public enum PlanPriceBandScopeKind {
    TEAM,
    ORG
}
