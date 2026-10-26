package com.mannschaft.app.payment.escrow.event;

/**
 * F03.11.1 募集キャンセル料の徴収失敗（payment → recruitment・設計書 §3.8）。
 *
 * @param cancellationRecordId キャンセル記録 ID
 * @param reason               ログ・運用向けの文字列（利用者には出さない）
 */
public record RecruitmentCancellationFeeChargeFailedEvent(
        Long cancellationRecordId,
        String reason) {
}
