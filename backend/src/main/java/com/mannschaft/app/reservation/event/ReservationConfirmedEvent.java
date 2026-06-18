package com.mannschaft.app.reservation.event;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 予約確定イベント（F03.4 自動確定／手動承認共通）。
 *
 * <p>予約が CONFIRMED へ遷移した際に発行される純データイベント。
 * AUTO モードでの自動確定時（{@code createReservation}）と、
 * 管理者による手動承認時（{@code confirmReservation}）の両方で発行される。
 * リマインド生成（丙隊）・将来の確定通知のトリガとして利用される。</p>
 *
 * <p>イベントは AFTER_COMMIT で副作用を逃がす設計のため、本イベントには
 * 主トランザクションがコミットした確定済み予約の情報のみを保持する。</p>
 */
@Getter
public class ReservationConfirmedEvent {

    /** チームID。 */
    private final Long teamId;

    /** 予約ID。 */
    private final Long reservationId;

    /** 確定を引き起こした操作者のユーザーID（AUTO 時は予約者本人、手動時は承認者）。 */
    private final Long actorUserId;

    /** スロットの開始日時（{@code slotDate} + {@code startTime} を合成した値）。リマインド基準時刻に用いる。 */
    private final LocalDateTime slotStartAt;

    /** スロットのタイトル。 */
    private final String slotTitle;

    public ReservationConfirmedEvent(Long teamId, Long reservationId, Long actorUserId,
                                     LocalDateTime slotStartAt, String slotTitle) {
        this.teamId = teamId;
        this.reservationId = reservationId;
        this.actorUserId = actorUserId;
        this.slotStartAt = slotStartAt;
        this.slotTitle = slotTitle;
    }
}
