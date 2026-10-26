package com.mannschaft.app.payment.entity;

/** 受益者退会による即時取消しの Stripe 同期状態。 */
public enum MembershipBeneficiaryWithdrawalCancellationStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
