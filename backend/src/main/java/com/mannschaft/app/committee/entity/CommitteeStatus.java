package com.mannschaft.app.committee.entity;

/**
 * 委員会のステータス。
 */
public enum CommitteeStatus {
    /** 草案（設立前） */
    DRAFT,
    /** 活動中 */
    ACTIVE,
    /** 閉幕 */
    CLOSED,
    /** アーカイブ済み */
    ARCHIVED,
    /** 草案キャンセル */
    CANCELLED_DRAFT
}
