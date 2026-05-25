package com.mannschaft.app.reservation.event;

import com.mannschaft.app.reservation.ApprovalMode;
import lombok.Getter;

/**
 * 予約作成イベント（F10.7 業務アラート用）。
 *
 * <p>予約が作成された直後に発行され、管理者への通知に利用される。</p>
 */
@Getter
public class ReservationCreatedEvent {
    private final Long teamId;
    private final Long reservationId;
    private final Long actorUserId;
    private final ApprovalMode approvalMode;
    private final String slotTitle;
    private final String bookedAtFormatted;

    public ReservationCreatedEvent(Long teamId, Long reservationId, Long actorUserId,
                                   ApprovalMode approvalMode, String slotTitle, String bookedAtFormatted) {
        this.teamId = teamId;
        this.reservationId = reservationId;
        this.actorUserId = actorUserId;
        this.approvalMode = approvalMode;
        this.slotTitle = slotTitle;
        this.bookedAtFormatted = bookedAtFormatted;
    }
}
