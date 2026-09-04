package com.mannschaft.app.advertising;

/**
 * 広告主の決済方式。
 */
public enum BillingMethod {

    /** クレジットカード決済（Stripe）。既定値・唯一の新規選択肢。 */
    STRIPE,

    /**
     * 後払い（請求書方式）。
     *
     * @deprecated F08.12 §5.0 により廃止済み。与信審査・延滞制裁・取消のいずれも実装されておらず、
     * 本番データが無いうちに新規選択を止めた（{@code AdvertiserAccountService.register} で拒否）。
     * 既存の {@code INVOICE} 行を壊さないため、enum 値自体と DB の {@code ENUM} 定義は残す。
     * 将来、与信審査つきの大口向けとして復活させる可能性があるための保持であり、削除しない。
     */
    @Deprecated
    INVOICE
}
