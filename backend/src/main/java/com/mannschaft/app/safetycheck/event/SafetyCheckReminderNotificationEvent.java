package com.mannschaft.app.safetycheck.event;

/**
 * 安否確認リマインドの通知発火イベント（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code SafetyCheckService#sendReminder} は業務トランザクションの内側で本イベントを publish する
 * だけに留める。<b>業務上の事実（ID）だけ</b>を積み、通知の文面組み立て
 * （{@code userLocaleCache.getLocale} によるロケール解決・{@code messageSource.getMessage} による
 * 件名/本文組み立て）は行わない。組み立ては {@link SafetyCheckReminderNotificationListener}
 * （{@code AFTER_COMMIT}）側の責務である（型確立PR #2910 の
 * {@code ContactRequestNotificationEvent} と同型。Issue #2871 の教訓「配信が後で起きるなら
 * 描画済み文字列を先に作って持ち回るな」）。</p>
 *
 * @param safetyCheckId   安否確認ID（{@code sourceId} に使う）
 * @param recipientUserId 通知の宛先ユーザーID（リマインド操作者）
 * @param scopeType       安否確認のスコープ種別名（{@code NotificationScopeType} の名称と一致する）
 * @param scopeId         安否確認のスコープID
 */
public record SafetyCheckReminderNotificationEvent(
        Long safetyCheckId,
        Long recipientUserId,
        String scopeType,
        Long scopeId) {
}
