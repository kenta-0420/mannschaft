package com.mannschaft.app.event;

/**
 * イベントステータス。イベントのライフサイクル状態を表す。
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    REGISTRATION_OPEN,
    REGISTRATION_CLOSED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
