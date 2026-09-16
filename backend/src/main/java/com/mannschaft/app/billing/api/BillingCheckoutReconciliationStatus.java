package com.mannschaft.app.billing.api;

/** V198 {@code billing_checkout_reconciliations.status}（{@code chk_bcr_status} と一致させること）。 */
public enum BillingCheckoutReconciliationStatus {
    /** 未回収。SQL 一発で回収対象を数える際の条件はこの値。 */
    PENDING,
    /** 照合が完了し、Stripe 側 Session と DB 側の状態が整合した。 */
    RESOLVED,
    /** 回収不能と判断して打ち切った（運用が明示的に落とす）。 */
    FAILED
}
