package com.mannschaft.app.billing.tax;

import java.time.Instant;

/**
 * 税コード更新リクエスト（決定6・AC-6）。
 *
 * <p>{@code rateBasisPoints}・{@code code}・{@code validFrom} は不変項目のため、
 * このリクエスト型そのものにフィールドを持たせないことでコンパイル時に変更不可を強制する。</p>
 *
 * @param displayName   表示名
 * @param stripeTaxCode Stripe 側税コード
 * @param validUntil    適用終了日時（null は無期限）
 * @param enabled       有効フラグ
 */
public record BillingTaxCodeUpdateRequest(
        String displayName, String stripeTaxCode, Instant validUntil, boolean enabled) {
}
