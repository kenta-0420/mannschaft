package com.mannschaft.app.parking;

/**
 * 来場者予約のステータス。
 */
public enum VisitorReservationStatus {
    PENDING_APPROVAL,
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    REJECTED,
    NO_SHOW
}
