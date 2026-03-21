package com.mannschaft.app.ticket;

/**
 * 回数券の決済方法。
 */
public enum PaymentMethod {

    /** Stripe オンライン決済 */
    STRIPE,

    /** 現金 */
    CASH,

    /** 店頭カード決済 */
    CARD_ON_SITE,

    /** 電子マネー */
    E_MONEY,

    /** その他 */
    OTHER
}
