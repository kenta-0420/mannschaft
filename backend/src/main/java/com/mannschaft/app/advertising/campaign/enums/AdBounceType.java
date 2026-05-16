package com.mannschaft.app.advertising.campaign.enums;

/**
 * F09.17 メールバウンス種別。
 * HARD: 課金対象外。COMPLAINT も HARD 同等扱い (設計書 §11 解決事項 8)。
 * SOFT: 課金対象。
 */
public enum AdBounceType {
    HARD,
    SOFT,
    COMPLAINT
}
