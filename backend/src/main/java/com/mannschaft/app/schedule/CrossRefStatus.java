package com.mannschaft.app.schedule;

/**
 * クロスチーム招待のステータス。
 */
public enum CrossRefStatus {
    /** 招待送信済み */
    PENDING,
    /** 確認待ち */
    AWAITING_CONFIRMATION,
    /** 承認済み */
    ACCEPTED,
    /** 拒否 */
    REJECTED,
    /** キャンセル */
    CANCELLED
}
