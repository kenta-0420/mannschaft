package com.mannschaft.app.receipt;

/**
 * 領収書のステータス。
 */
public enum ReceiptStatus {
    /** 下書き（採番・PDF生成なし） */
    DRAFT,
    /** 発行済み */
    ISSUED
}
