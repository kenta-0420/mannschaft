package com.mannschaft.app.reservation;

/**
 * 予約ステータス。予約のライフサイクル状態を表す。
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    COMPLETED,
    NO_SHOW
}
