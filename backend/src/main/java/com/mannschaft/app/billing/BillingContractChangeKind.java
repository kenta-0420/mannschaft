package com.mannschaft.app.billing;

/**
 * Billing Center PR6b: {@code billing_contract_changes.kind}（VARCHAR(16) + CHECK）。
 *
 * <p>値集合は V196（{@code chk_bcc_kind}）と<b>完全一致</b>させること。値を増減・改名する場合は
 * 必ず新規 Flyway migration で CHECK 制約側も同時に更新する。</p>
 *
 * <p>正本 DDL: {@code backend/src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql}
 * {@code CONSTRAINT chk_bcc_kind CHECK (kind IN ('UPGRADE','DOWNGRADE'))}。</p>
 *
 * <p>整合性は {@code BillingContractChangeEntityDdlAlignmentTest}
 * （V196 の SQL ファイルを実読して照合）で機械担保する。</p>
 */
public enum BillingContractChangeKind {
    /** PLAN のアップグレード（即時反映・PR6b-1 の担当）。 */
    UPGRADE,
    /** PLAN のダウングレード（期末反映・Stripe Subscription Schedule 経由・PR6b-2 の担当）。 */
    DOWNGRADE
}
