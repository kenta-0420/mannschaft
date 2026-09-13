package com.mannschaft.app.billing;

/**
 * Billing Center PR6a: {@code billing_contract_operations.kind} の状態機械（VARCHAR(24) + CHECK）。
 *
 * <p>値集合は V196（{@code chk_bco_kind}）と<b>完全一致</b>させること。値を増減・改名する場合は
 * 必ず新規 Flyway migration で CHECK 制約側も同時に更新する（本 Entity 側だけを直しても DB は
 * 古い値集合のまま拒否する）。</p>
 *
 * <p>正本 DDL: {@code backend/src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql}
 * {@code CONSTRAINT chk_bco_kind CHECK (kind IN ('PLAN_CHANGE','CANCEL','RESUME','DOWNGRADE_TO_CANCEL',
 * 'MIGRATION','MEMBER_REPRICE','REFUND'))}。</p>
 *
 * <p>整合性は {@code BillingContractOperationEntityDdlAlignmentTest}
 * （V196 の SQL ファイルを実読して照合）で機械担保する。</p>
 */
public enum BillingOperationKind {
    /** PLAN 変更（UPGRADE/DOWNGRADE。詳細行は {@code billing_contract_changes} に投影）。 */
    PLAN_CHANGE,
    /** 契約解約（cancel_at_period_end 予約または即時解約）。 */
    CANCEL,
    /** 解約予約の取消（期末解約 → 継続への復帰）。 */
    RESUME,
    /** PLAN ダウングレード確定時の期末解約（{@code billing_contract_changes} の DOWNGRADE 系と対）。 */
    DOWNGRADE_TO_CANCEL,
    /** legacy Customer 移行（詳細行は {@code billing_customer_migrations} に投影）。 */
    MIGRATION,
    /** 次暦月の人数band価格変更（詳細行は {@code billing_membership_price_adjustments} に投影）。 */
    MEMBER_REPRICE,
    /** 返金（詳細行は {@code billing_invoice_adjustments} に投影）。 */
    REFUND
}
