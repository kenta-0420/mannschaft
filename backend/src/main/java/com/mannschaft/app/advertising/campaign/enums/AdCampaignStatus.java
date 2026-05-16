package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 メッセージ型キャンペーン状態。
 * 状態遷移マシン詳細は設計書 §5 を参照。
 */
public enum AdCampaignStatus {
    DRAFT,
    REVIEW,
    APPROVED,
    SCHEDULED,
    DELIVERING,
    PAUSED,
    COMPLETED,
    BLOCKED,
    CANCELLED
}
