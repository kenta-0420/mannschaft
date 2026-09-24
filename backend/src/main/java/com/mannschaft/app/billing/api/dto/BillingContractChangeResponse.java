package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingContractChangeStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing Center PR6b-1 B群: 変更の実行（{@code POST …/changes}）の応答（AC-25）。
 *
 * <p><b>{@code clientSecret} は返さない</b>（AC-25）。3DS の client secret は
 * {@code GET …/changes/{changeId}/payment-action} が都度取得する（第8隊の担当）。</p>
 *
 * @param changeId    起票された {@code billing_contract_changes.id}
 * @param status      現在の状態
 * @param effectiveAt 効力発生の見積り
 */
public record BillingContractChangeResponse(
        UUID changeId, BillingContractChangeStatus status, Instant effectiveAt) {
}
