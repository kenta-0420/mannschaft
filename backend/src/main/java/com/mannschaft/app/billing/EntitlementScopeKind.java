package com.mannschaft.app.billing;

/**
 * F20.1 課金・エンタイトルメント基盤: 操作スコープの種別。
 *
 * <p>{@code entitlements.scope_kind} / {@code billing_contracts.scope_kind} /
 * {@code active_contract_pointers.scope_kind} に対応する（VARCHAR(8) + CHECK）。
 * {@code com.mannschaft.app.payment.connect.ScopeKind}（USER/TEAM/ORG）と値・綴りを一致させるが、
 * billing ドメインはクロスドメインの enum を直接参照しないため本 enum を新設する
 * （設計書 README §1.1）。</p>
 *
 * <p><b>注意</b>: {@code com.mannschaft.app.membership.domain.ScopeType}（ORGANIZATION/TEAM）とは
 * 別物（値も綴りも異なる）。混同禁止。</p>
 */
public enum EntitlementScopeKind {
    USER,
    TEAM,
    ORG
}
