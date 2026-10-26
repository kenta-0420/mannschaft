package com.mannschaft.app.billing;

/**
 * Billing Center PR6a: {@code billing_contract_operations.status} の状態機械（VARCHAR(24) + CHECK）。
 *
 * <p>値集合は V196（{@code chk_bco_status}）と<b>完全一致</b>させること。正本 DDL:
 * {@code backend/src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql}
 * {@code CONSTRAINT chk_bco_status CHECK (status IN ('CREATED','CALLING_STRIPE','APPLIED','FAILED',
 * 'RECONCILIATION_REQUIRED','CANCELLED'))}。</p>
 *
 * <h2>許可される遷移（実装のガードは第5隊が入れる。ここでは値集合と意味のみ固定する）</h2>
 * <pre>
 * CREATED --------------------------&gt; CALLING_STRIPE
 * CALLING_STRIPE --------------------&gt; APPLIED
 * CALLING_STRIPE --------------------&gt; FAILED
 * CALLING_STRIPE --------------------&gt; RECONCILIATION_REQUIRED
 * CALLING_STRIPE --------------------&gt; CANCELLED
 * RECONCILIATION_REQUIRED -----------&gt; APPLIED   (reconcile による確定)
 * RECONCILIATION_REQUIRED -----------&gt; FAILED    (reconcile による確定)
 * RECONCILIATION_REQUIRED -----------&gt; CANCELLED (reconcile による確定)
 * CREATED ----------------------------&gt; CANCELLED (Stripe呼出前・停止窓の回収で取消)
 * </pre>
 *
 * <p>{@link #APPLIED} / {@link #FAILED} / {@link #CANCELLED} は terminal。
 * {@link #RECONCILIATION_REQUIRED} は terminal <b>ではない</b>検疫状態（reconcile ジョブが
 * 確定先の3状態のいずれかへ必ず遷移させる）。</p>
 *
 * <p>整合性は {@code BillingContractOperationEntityDdlAlignmentTest}
 * （V196 の SQL ファイルを実読して照合）で機械担保する。</p>
 */
public enum BillingOperationStatus {
    /** 起票直後・Stripe未呼出。 */
    CREATED,
    /** Stripe API 呼出中（呼出前後で idempotency_key により再実行安全）。 */
    CALLING_STRIPE,
    /** 確定・成功（terminal）。 */
    APPLIED,
    /** 確定・失敗（terminal）。 */
    FAILED,
    /**
     * Stripe呼出結果が不明（タイムアウト等）で応答が確認できない検疫状態（<b>terminalではない</b>）。
     * reconcile ジョブが Stripe 側の実態を照会し、APPLIED/FAILED/CANCELLED のいずれかへ確定させる。
     */
    RECONCILIATION_REQUIRED,
    /** 取消済み（terminal。Stripe呼出前の停止窓回収、または reconcile による取消確定）。 */
    CANCELLED
}
