package com.mannschaft.app.payment;

/**
 * 第三者代理払い許可（payment_proxy_grants）のステータス。
 *
 * <p>遷移: {@code PENDING}（招待発行）→ {@code ACTIVE}（払い手が受諾）→
 * {@code REVOKED}（受益者/払い手が取消）または {@code EXPIRED}（effective_until 超過）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3</p>
 */
public enum PaymentProxyGrantStatus {

    /** 招待発行済み・払い手未受諾。 */
    PENDING,

    /** 払い手が受諾済み・有効。 */
    ACTIVE,

    /** 受益者または払い手が取消。 */
    REVOKED,

    /** effective_until 超過による自動失効。 */
    EXPIRED
}
