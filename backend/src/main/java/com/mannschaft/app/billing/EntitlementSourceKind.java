package com.mannschaft.app.billing;

/**
 * F20.1: {@code entitlements.source_kind} の発行元区分（VARCHAR(12) + CHECK）。
 *
 * <p>{@code source_ref_id} の参照先: PLAN/ADDON={@code billing_contracts.id} ／
 * BETA_GRANT={@code beta_grants.id}（F20.3・同一 billing ドメインのサブパッケージ
 * {@code billing.beta}）。設計書 01 §3.2。</p>
 */
public enum EntitlementSourceKind {
    PLAN,
    ADDON,
    BETA_GRANT
}
