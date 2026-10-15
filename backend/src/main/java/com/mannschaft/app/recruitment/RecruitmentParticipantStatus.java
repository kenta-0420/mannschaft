package com.mannschaft.app.recruitment;

/**
 * F03.11 参加者のステータス。
 * Phase 1+5a で扱うのは APPLIED/CONFIRMED/WAITLISTED/CANCELLED/ATTENDED。
 * NO_SHOW は Phase 5b で活用。
 */
public enum RecruitmentParticipantStatus {
    APPLIED,
    CONFIRMED,
    WAITLISTED,
    CANCELLED,
    NO_SHOW,
    ATTENDED
}
