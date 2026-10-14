package com.mannschaft.app.reservation.event;

import java.time.LocalDateTime;

/**
 * 予約リマインドの通知配送要求イベント（Issue #2997 / CMP-260827-1152 G3）。
 *
 * <p>{@code ReservationReminderDispatchRunner#publishOne} の項目TX（{@code REQUIRES_NEW}）が commit される直前に
 * publish し、{@link ReservationReminderDispatchNotificationListener} が {@code AFTER_COMMIT} で受け取る。
 * 項目TXがロールバックすれば配送要求は発火しない。</p>
 *
 * <h2>イベントに載せる値</h2>
 * <p>バッチは対象抽出時に reservation / slot を<b>一括取得</b>済み（N+1 対策）なので、文面の材料
 * （枠の開始日時・タイトル）はそのまま載せる。配送側で読み直すと 1 件ごとに SELECT が増えるため。
 * 載せているのは予約者ID・チームID・枠の開始日時・枠タイトルのみで、氏名・メール等の PII は含まない。</p>
 *
 * @param reminderId    リマインダーID（送信済みの印 = {@code reservation_reminders.status} の対象）
 * @param reservationId 予約ID（通知の source）
 * @param teamId        チームID
 * @param userId        予約者（受信者）ユーザーID
 * @param slotStartAt   枠の開始日時（業務ローカル時刻）
 * @param slotTitle     枠タイトル（無ければ {@code null}）
 */
public record ReservationReminderDispatchEvent(
        Long reminderId,
        Long reservationId,
        Long teamId,
        Long userId,
        LocalDateTime slotStartAt,
        String slotTitle) {
}
