package com.mannschaft.app.payment.event;

import java.util.List;

/**
 * 未払いリマインドの通知発火イベント（Issue #2990 L7）。
 *
 * <p>{@code MemberPaymentService#sendRemind} は業務トランザクションの内側で本イベントを publish するだけに留め、
 * 実配送は {@link PaymentRemindNotificationListener}（{@code AFTER_COMMIT} ＋ {@code @Async("event-pool")}）が
 * 受信者ごとに独立トランザクションで行う。</p>
 *
 * <p><b>イベントには ID だけを載せる。</b>支払項目名などの描画済み文字列や日時は載せず、
 * リスナーが支払項目を読み直して受信者ごとの locale で組み立てる。</p>
 *
 * @param paymentItemId 対象の支払項目ID（{@code sourceId} とアクションURLに使う）
 * @param teamId        チームスコープの支払項目ならチームID（協会スコープなら {@code null}）
 * @param scopeId       通知スコープID（{@code teamId} が無ければ organizationId）
 * @param recipientUserIds 未払いメンバーのユーザーID一覧
 */
public record PaymentRemindNotificationEvent(
        Long paymentItemId,
        Long teamId,
        Long scopeId,
        List<Long> recipientUserIds) {
}
