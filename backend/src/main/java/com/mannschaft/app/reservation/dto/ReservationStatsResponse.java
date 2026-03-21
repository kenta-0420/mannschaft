package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReservationStatsResponse {

    private final long totalReservations;
    private final long pendingCount;
    private final long confirmedCount;
    private final long cancelledCount;
    private final long completedCount;
    private final long noShowCount;
}
