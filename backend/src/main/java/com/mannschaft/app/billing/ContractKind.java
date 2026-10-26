package com.mannschaft.app.billing;

/**
 * F20.1: {@code billing_contracts.contract_kind} / {@code active_contract_pointers.contract_kind}
 * の区分（VARCHAR(8) + CHECK）。
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.1。</p>
 */
public enum ContractKind {
    /** プラン契約（{@code plan_key} 必須・{@code feature_key} は NULL）。 */
    PLAN,
    /** アドオン契約（{@code feature_key} 必須・{@code plan_key} は NULL）。 */
    ADDON
}
