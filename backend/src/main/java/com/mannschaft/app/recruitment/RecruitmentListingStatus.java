package com.mannschaft.app.recruitment;

/**
 * F03.11 募集枠のライフサイクルステータス。
 * Phase 1+5a で扱うのは DRAFT/OPEN/FULL/CLOSED/CANCELLED の5値。
 * AUTO_CANCELLED/COMPLETED は Phase 3 以降で活用。
 */
public enum RecruitmentListingStatus {
    DRAFT,
    OPEN,
    FULL,
    CLOSED,
    CANCELLED,
    AUTO_CANCELLED,
    COMPLETED
}
