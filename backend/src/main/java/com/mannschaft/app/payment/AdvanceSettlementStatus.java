package com.mannschaft.app.payment;

/**
 * 協会請求の立替/精算記録（team_payment_advances）の精算ステータス（案3）。
 *
 * <p>遷移: {@code PENDING}（協会請求支払い時に立替起票）→ {@code SETTLED}（チームから ADMIN へ精算が行われ、
 * F04.9 確認必須通知でチーム ADMIN が確認）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.5 / README §6.3。</p>
 */
public enum AdvanceSettlementStatus {

    /** 立替済み・チームからの精算未確認。 */
    PENDING,

    /** チームから精算され、チーム ADMIN が確認済み。 */
    SETTLED
}
