package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 予約レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReservationResponse {

    private final Long id;
    private final Long reservationSlotId;
    private final Long lineId;
    private final Long teamId;
    private final Long userId;
    private final String status;
    private final LocalDateTime bookedAt;
    private final LocalDateTime confirmedAt;
    private final LocalDateTime cancelledAt;
    private final String cancelReason;
    private final String cancelledBy;
    private final LocalDateTime completedAt;
    private final String userNote;
    private final String adminNote;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
