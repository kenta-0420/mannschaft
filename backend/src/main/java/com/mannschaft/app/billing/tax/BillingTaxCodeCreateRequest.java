package com.mannschaft.app.billing.tax;

import java.time.Instant;

/**
 * 税コード新規登録リクエスト（決定6・AC-5）。
 *
 * @param code             税コード（例: JP_STANDARD_10）
 * @param displayName      表示名
 * @param rateBasisPoints  税率（basis points。0〜10000）
 * @param stripeTaxCode    Stripe 側税コード（null 許容）
 * @param validFrom        適用開始日時
 * @param validUntil       適用終了日時（null は無期限）
 * @param enabled          有効フラグ
 */
public record BillingTaxCodeCreateRequest(
        String code, String displayName, int rateBasisPoints, String stripeTaxCode,
        Instant validFrom, Instant validUntil, boolean enabled) {
}
