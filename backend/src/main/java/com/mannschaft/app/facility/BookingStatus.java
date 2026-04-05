package com.mannschaft.app.facility;

/**
 * 施設予約ステータス。
 */
public enum BookingStatus {
    PENDING_APPROVAL,
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    REJECTED,
    NO_SHOW
}
