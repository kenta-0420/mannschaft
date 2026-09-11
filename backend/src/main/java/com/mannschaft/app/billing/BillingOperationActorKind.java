package com.mannschaft.app.billing;

/**
 * Billing Center PR6a: {@code billing_contract_operations.actor_kind}（VARCHAR(8) + CHECK）。
 *
 * <p>正本 DDL: {@code CONSTRAINT chk_bco_actor CHECK (
 * (actor_kind = 'USER' AND created_by IS NOT NULL)
 * OR (actor_kind = 'SYSTEM' AND created_by IS NULL))}。
 * {@code created_by} の NULL 許容性と対で管理する（USER は created_by 必須、SYSTEM は NULL 必須）。</p>
 */
public enum BillingOperationActorKind {
    /** ユーザー操作起点（{@code created_by} 必須）。 */
    USER,
    /** システム起点（reconcile ジョブ・webhook 等。{@code created_by} は NULL）。 */
    SYSTEM
}
