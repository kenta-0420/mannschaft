package com.mannschaft.app.payment.event;

import java.util.UUID;

/**
 * 立替金の精算確定（PENDING → SETTLED）の通知発火イベント（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code TeamPaymentAdvanceService#confirmSettlement} は業務トランザクション
 * （{@code team_payment_advances} の SETTLED 化）の内側で本イベントを publish するだけに留める。
 * 協会 ADMIN の解決・ロケール解決・件名/本文の組み立ては
 * {@code PaymentAdvanceSettledNotificationListener}（{@code AFTER_COMMIT}）側で行う。</p>
 *
 * <h2>金額・通貨を載せる理由</h2>
 * <p>本文に埋め込む {@code {0} {1}} の引数であり、<b>描画済み文字列ではなく業務上の事実</b>である。
 * 立替行はコミット後も生存しているため読み直すこともできるが、金額・通貨は不変値であり
 * 余分な DB 往復を避けるためイベントに載せる。</p>
 *
 * @param advanceId       立替記録ID（ログの相関キー）
 * @param organizationId  通知先の協会（組織）ID
 * @param advancedAmount  立替額
 * @param currency        通貨
 * @param actorUserId     精算を確認したチーム ADMIN のユーザーID（{@code actorId}）
 */
public record PaymentAdvanceSettledNotificationEvent(
        UUID advanceId,
        Long organizationId,
        Integer advancedAmount,
        String currency,
        Long actorUserId) {
}
