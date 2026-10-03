package com.mannschaft.app.shift.event;

import java.util.List;

/**
 * シフト交代申請の期限切れ自動キャンセル通知の配送要求イベント（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code ShiftSwapExpiryRunner#cancelOne} が 1 申請ぶんのキャンセルを独立トランザクションで
 * コミットする直前に publish し、{@link ShiftSwapExpiredNotificationListener} が
 * {@code AFTER_COMMIT} で受け取る。</p>
 *
 * @param swapId           交代申請ID（通知の source。キャンセルは行を削除せず status を更新するだけなので生存している）
 * @param recipientUserIds 受信者（申請者、および相手が決まっていればその相手）
 */
public record ShiftSwapExpiredNotificationEvent(
        Long swapId,
        List<Long> recipientUserIds) {
}
