package com.mannschaft.app.recruitment.event;

/**
 * F03.11.1 募集キャンセル料の徴収要求（recruitment → payment・設計書 §3.8）。
 *
 * <p>キャンセル成立（TX-A）の末尾で、キャンセル料が発生する個人申込についてのみ発火する。
 * payment 側は {@code @TransactionalEventListener(AFTER_COMMIT)} で受け、キャンセルの
 * トランザクションがコミットされた後にのみ徴収を始める（§3.3）。</p>
 *
 * <p>クロスドメインのイベントゆえ ID と素の値のみを運ぶ（Entity を載せない）。</p>
 *
 * @param cancellationRecordId キャンセル記録 ID（冪等キーの素・§7.1）
 * @param listingId            募集 ID（escrow 引き当ての三つ組）
 * @param participantId        参加者 ID（escrow 引き当ての三つ組）
 * @param payerUserId          支払者ユーザー ID
 * @param feeAmount            キャンセル料（円・丸め後・§6.1。最小通貨単位への換算は payment 側の責務）
 */
public record RecruitmentCancellationFeeChargeRequestedEvent(
        Long cancellationRecordId,
        Long listingId,
        Long participantId,
        Long payerUserId,
        int feeAmount) {
}
