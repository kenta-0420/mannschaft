package com.mannschaft.app.family;

/**
 * ケア対象者イベント通知の種別。F03.12 / Phase3 通知ログでも使用する。
 */
public enum EventCareNotificationType {
    RSVP_CONFIRMED,
    CHECKIN,
    CHECKOUT,
    NO_CONTACT_CHECK,
    ABSENT_ALERT,
    DISMISSAL
}
