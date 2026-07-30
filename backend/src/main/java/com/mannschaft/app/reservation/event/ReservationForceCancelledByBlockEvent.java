package com.mannschaft.app.reservation.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定期予約不可枠の<b>強行登録</b>により既存予約が管理者キャンセルされたことを表すイベント
 * （F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30）。
 *
 * <p>管理者が {@code forceCancelConflicting=true} でルールを登録/更新すると、90 日 horizon 内で
 * overlap する active 予約が一括キャンセルされる。<b>予約を勝手に消して黙っているのは許されない</b>ため、
 * 各申込者へ必ず通知する。通知は AFTER_COMMIT のリスナー
 * （{@code ReservationForceCancelNotificationEventListener}）で行い、
 * 登録トランザクションが確定してから初めて送る（ロールバックされた「幻のキャンセル通知」を作らない）。</p>
 *
 * @see com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService
 */
@Getter
@RequiredArgsConstructor
public class ReservationForceCancelledByBlockEvent {

    /** チームID（通知の scopeId）。 */
    private final Long teamId;

    /** キャンセルされた予約ID（通知の sourceId）。 */
    private final Long reservationId;

    /** 申込者ユーザーID（通知の宛先）。 */
    private final Long userId;

    /** 枠の開始日時（本文に載せる。「7月21日 19:00 のご予約」）。 */
    private final LocalDateTime slotStartAt;

    /** 枠タイトル（本文に載せる。null 可）。 */
    private final String slotTitle;

    /** 管理者が入力した事由ラベル（「研修」等・本文に載せる。null 可）。 */
    private final String blockReason;
}
