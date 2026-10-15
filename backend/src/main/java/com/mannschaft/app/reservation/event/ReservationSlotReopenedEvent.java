package com.mannschaft.app.reservation.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約枠が満席（FULL）から空き（AVAILABLE）へ転じた瞬間に発行されるイベント（F03.4.5 §6.1）。
 *
 * <p>単枠キャンセル・却下・リスケジュール（旧枠復帰）・グループ一括キャンセル（N 枠それぞれ）の
 * すべての空き復帰点である {@code ReservationSlotService.decrementAndReopen} から、満席が解消された
 * 場合にのみ発行される。{@code @TransactionalEventListener(AFTER_COMMIT)} で購読され、当該枠の
 * WAITING 登録者へ一斉通知する（{@link ReservationWaitlistNotificationEventListener}）。</p>
 *
 * <p>グループ一括キャンセルでは枠ごとに 1 イベントが発行されるため、枠単位で重複なく通知される
 * （1 ユーザーは 1 枠に 1 WAITING エントリのみを持つ・§6.1）。</p>
 */
@Getter
@RequiredArgsConstructor
public class ReservationSlotReopenedEvent {

    /** チームID。 */
    private final Long teamId;

    /** 空きに転じた枠ID。 */
    private final Long slotId;
}
