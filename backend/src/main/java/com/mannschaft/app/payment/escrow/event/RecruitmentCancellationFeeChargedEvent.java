package com.mannschaft.app.payment.escrow.event;

import com.mannschaft.app.payment.escrow.SettleCancellationFeeOutcome;

/**
 * F03.11.1 募集キャンセル料の徴収成功（payment → recruitment・設計書 §3.8）。
 *
 * @param cancellationRecordId キャンセル記録 ID
 * @param stripeReference      Stripe の参照 ID（{@code pi_...} または {@code re_...}・§3.7）
 * @param outcome              どの経路で徴収したか（§3.4）
 */
public record RecruitmentCancellationFeeChargedEvent(
        Long cancellationRecordId,
        String stripeReference,
        SettleCancellationFeeOutcome outcome) {
}
