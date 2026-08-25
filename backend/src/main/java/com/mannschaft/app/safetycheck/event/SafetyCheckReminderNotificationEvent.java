package com.mannschaft.app.safetycheck.event;

import com.mannschaft.app.safetycheck.SafetyCheckScopeType;

/**
 * 安否確認リマインド送信時に発火する通知イベント（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code SafetyCheckService#sendReminder} は業務トランザクションの内側で本イベントを
 * publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、通知の文面組み立て
 * （ロケール解決・件名/本文組み立て）は行わない（{@code SafetyCheckReminderNotificationListener}
 * が {@code AFTER_COMMIT} で行う）。</p>
 *
 * @param safetyCheckId 安否確認ID（{@code sourceId} に使う）
 * @param recipientId   通知の宛先ユーザーID（リマインド送信を実行した操作者自身）
 * @param scopeType     安否確認のスコープ種別
 * @param scopeId       安否確認のスコープID
 */
public record SafetyCheckReminderNotificationEvent(
        Long safetyCheckId, Long recipientId, SafetyCheckScopeType scopeType, Long scopeId) {
}
