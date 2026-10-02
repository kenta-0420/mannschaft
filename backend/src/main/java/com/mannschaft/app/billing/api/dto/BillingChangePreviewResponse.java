package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingContractChangeKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing Center PR6b-1 A群: 事前見積り（{@code POST …/change-previews}）の応答（AC-1）。
 *
 * @param previewId    発行された preview の ID（AC-3: UUID そのもの）
 * @param kind         変更種別（本 PR は {@link BillingContractChangeKind#UPGRADE} のみ到達）
 * @param amountDueNow AC-2: Stripe の見積り API が返した金額をそのまま格納する
 * @param effectiveAt  変更が反映される瞬間の見積り
 * @param expiresAt    preview の有効期限（AC-5/AC-6: 最大10分・半開区間）
 */
public record BillingChangePreviewResponse(
        UUID previewId,
        BillingContractChangeKind kind,
        Money amountDueNow,
        Instant effectiveAt,
        Instant expiresAt) {

    /** {@code BillingQuoteResponse.Money} と同一形状（正本 05:393）。 */
    public record Money(String currency, long amountIncludingTax, long amountExcludingTax,
                        long taxAmount, String taxName, Integer taxRateBasisPoints) {
    }
}
