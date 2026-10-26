package com.mannschaft.app.reservation.event;

import lombok.Getter;

/**
 * メンバーによる予約キャンセルイベント（F10.7 業務アラート用）。
 *
 * <p>メンバー/サポーターが予約をキャンセルした際に発行され、管理者への通知に利用される。</p>
 */
@Getter
public class ReservationCancelledByMemberEvent {
    private final Long teamId;
    private final Long reservationId;
    private final Long actorUserId;
    private final String slotTitle;

    public ReservationCancelledByMemberEvent(Long teamId, Long reservationId,
                                              Long actorUserId, String slotTitle) {
        this.teamId = teamId;
        this.reservationId = reservationId;
        this.actorUserId = actorUserId;
        this.slotTitle = slotTitle;
    }
}
