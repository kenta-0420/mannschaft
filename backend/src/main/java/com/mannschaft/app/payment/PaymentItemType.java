package com.mannschaft.app.payment;

/**
 * 支払い項目種別。
 */
public enum PaymentItemType {
    ANNUAL_FEE,
    MONTHLY_FEE,
    ITEM,
    DONATION,
    /** 期別課金（単発 destination charge・term_starts_on〜term_ends_on で有効期間を指定） */
    TERM
}
