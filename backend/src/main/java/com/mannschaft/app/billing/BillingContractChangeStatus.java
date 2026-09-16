package com.mannschaft.app.billing;

/**
 * Billing Center PR6b: {@code billing_contract_changes.status}（VARCHAR(24) + CHECK）。
 *
 * <p>値集合は V196（{@code chk_bcc_status}）と<b>完全一致</b>させること。正本 DDL:
 * {@code backend/src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql}
 * {@code CONSTRAINT chk_bcc_status CHECK (status IN ('PENDING_PAYMENT','REQUIRES_ACTION',
 * 'CREATING_SCHEDULE','SCHEDULED','APPLIED','FAILED','CANCELLED'))}。</p>
 *
 * <h2>担当 PR（実装のガードは後続の隊が入れる。ここでは値集合と意味のみ固定する）</h2>
 * <ul>
 *     <li>{@link #PENDING_PAYMENT} / {@link #REQUIRES_ACTION} / {@link #APPLIED} / {@link #FAILED}
 *         — <b>PR6b-1（本 PR・upgrade）</b>が到達する</li>
 *     <li>{@link #CREATING_SCHEDULE} / {@link #SCHEDULED} — <b>PR6b-2（downgrade）</b>が到達する</li>
 *     <li>{@link #CANCELLED} — <b>PR6b-3（取り消し）</b>が到達する</li>
 * </ul>
 *
 * <p>{@code kind} との組合せは {@code chk_bcc_refs}（{@code stripe_schedule_ref} の NULL 可否）で
 * DB 側からも縛られる。詳細は {@link BillingContractChangeEntity} の {@code stripeScheduleRef}
 * Javadoc を参照。</p>
 *
 * <p>整合性は {@code BillingContractChangeEntityDdlAlignmentTest}
 * （V196 の SQL ファイルを実読して照合）で機械担保する。</p>
 */
public enum BillingContractChangeStatus {
    /** Stripe Invoice/PaymentIntent の決済待ち（UPGRADE 経路。PR6b-1）。 */
    PENDING_PAYMENT,
    /** 3DS 等の追加認証待ち（UPGRADE 経路。PR6b-1）。 */
    REQUIRES_ACTION,
    /** Stripe Subscription Schedule 作成中（DOWNGRADE 経路。PR6b-2）。 */
    CREATING_SCHEDULE,
    /** Schedule 作成済・期末反映待ち（DOWNGRADE 経路。PR6b-2）。 */
    SCHEDULED,
    /** 確定・成功（terminal。UPGRADE は即時反映、DOWNGRADE は期末反映で到達）。 */
    APPLIED,
    /** 確定・失敗（terminal）。 */
    FAILED,
    /** 取消済み（terminal・PR6b-3 の担当）。 */
    CANCELLED
}
