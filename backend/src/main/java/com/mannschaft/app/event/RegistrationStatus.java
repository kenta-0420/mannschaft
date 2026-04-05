package com.mannschaft.app.event;

/**
 * 参加登録ステータス。参加申込のライフサイクル状態を表す。
 */
public enum RegistrationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    WAITLISTED
}
