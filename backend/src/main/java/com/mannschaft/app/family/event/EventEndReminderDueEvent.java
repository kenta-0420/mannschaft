package com.mannschaft.app.family.event;

/**
 * 解散通知リマインドの配送要求イベント（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code EventEndReminderBatchService#runEndReminderCheck} は業務トランザクションの内側では
 * リマインド回数のインクリメントまでを行い、通知の実配送は本イベント経由で
 * {@code EventEndReminderDeliveryListener}（{@code AFTER_COMMIT}）へ委ねる。</p>
 *
 * <h2>載せるのは ID と段階だけ</h2>
 * <p>通知タイトル・本文はいずれも受信者の locale ごとに {@code MessageSource} で組み立てる
 * 描画済み文字列であり、イベントには載せない。イベント名（{@code subtitle} / {@code slug}）も
 * {@code events} 行から読み直す。日時型（{@code LocalDateTime}）を record コンポーネントに置くと
 * {@code DateTimeAndZoneGuardTest} が弾く。</p>
 *
 * @param eventId 対象イベントID
 * @param stage   リマインド段階（0＝1回目 NORMAL / 1＝2回目 HIGH / 2＝3回目 URGENT＋チームADMIN）
 */
public record EventEndReminderDueEvent(Long eventId, int stage) {
}
