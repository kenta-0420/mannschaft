package com.mannschaft.app.receipt;

/**
 * 発行待ちキューのステータス。
 */
public enum ReceiptQueueStatus {
    /** 承認待ち */
    PENDING,
    /** 承認済み（発行処理中） */
    APPROVED,
    /** スキップ（領収書不要） */
    SKIPPED
}
