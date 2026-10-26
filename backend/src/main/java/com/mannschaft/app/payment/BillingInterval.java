package com.mannschaft.app.payment;

/**
 * F08.9 継続課金の課金周期。
 *
 * <p>{@code payment_items.billing_interval}（is_recurring=TRUE 時）および
 * {@code membership_subscriptions.billing_interval} に対応する。
 * それぞれ {@code MONTHLY_FEE}/{@code ANNUAL_FEE}（{@link PaymentItemType}）と整合する。</p>
 *
 * <p>DB 側は VARCHAR(8) + CHECK 制約で表現する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.2 / §2.1</p>
 */
public enum BillingInterval {

    /** 月次課金（MONTHLY_FEE と整合）。 */
    MONTHLY,

    /** 年次課金（ANNUAL_FEE と整合）。 */
    YEARLY
}
