package com.mannschaft.app.payment;

/**
 * 代理払い許可（payment_proxy_grants）の権原発行経路。
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3</p>
 */
public enum PaymentProxyGrantedVia {

    /** 招待トークン経由（メール/QR等で払い手に送付）。 */
    INVITE_TOKEN,

    /** アプリ内操作経由（受益者が払い手を直接指定）。 */
    IN_APP
}
