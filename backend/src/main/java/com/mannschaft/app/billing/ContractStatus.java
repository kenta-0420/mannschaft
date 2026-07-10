package com.mannschaft.app.billing;

/**
 * F20.1: {@code billing_contracts.status} の状態機械（VARCHAR(12) + CHECK）。
 *
 * <p>{@code ACTIVE → CANCELLED}（解約 API）／{@code ACTIVE → EXPIRED}（Phase 2・期限到来/不払い。
 * ベータ中は遷移しない）。設計書 01 §4.2。</p>
 */
public enum ContractStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
