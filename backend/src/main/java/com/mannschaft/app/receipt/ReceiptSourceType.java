package com.mannschaft.app.receipt;

/**
 * 領収書の元データ種別（F08.12 §3.1 `receipts.source_type`）。
 *
 * <p>元データは receipt ドメインの外にあるため FK は張らず、{@link ReceiptSourceRef} が
 * 保持する文字列表現で論理参照する（クロスドメイン FK 禁止・設計原則 1）。</p>
 */
public enum ReceiptSourceType {

    /** 会費・イベント参加費の支払い実績（F08.4 団体スコープ）。 */
    MEMBER_PAYMENT,

    /** 広告費請求書（F19 `ad_invoices`。BIGINT 主キー）。 */
    AD_INVOICE,

    /** 通知プリペイドクレジット購入（F09.13 `notification_credit_purchases`。BIGINT 主キー）。 */
    NOTIFICATION_CREDIT_PURCHASE,

    /** 月謝サブスク請求（F20.1 `billing_invoices`。UUIDv7 主キー。第2段）。 */
    BILLING_INVOICE,

    /** 手動発行。PLATFORM スコープでは使用できない（§4.1 の検索 3 要件を満たせなくなるため）。 */
    MANUAL
}
