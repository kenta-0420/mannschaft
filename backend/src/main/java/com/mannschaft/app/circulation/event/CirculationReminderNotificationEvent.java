package com.mannschaft.app.circulation.event;

/**
 * 回覧文書の手動リマインド送信時に発火する通知イベント（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code CirculationService#remindDocument} は業務トランザクションの内側で本イベントを
 * publish するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、受信者リストの解決・通知の文面
 * 組み立ては行わない（{@code CirculationReminderNotificationListener} が {@code AFTER_COMMIT} で
 * 行う）。未押印受信者（{@code PENDING}）は業務コミット後に改めて解決するため、業務コミットの時点で
 * 確定していた対象と厳密に一致しない可能性があるが、リマインドという性質上、コミット後の最新状態を
 * 対象にすることに実害はない。</p>
 *
 * @param documentId 文書ID
 * @param actorId    リマインドを送信した操作者ユーザーID
 */
public record CirculationReminderNotificationEvent(Long documentId, Long actorId) {
}
