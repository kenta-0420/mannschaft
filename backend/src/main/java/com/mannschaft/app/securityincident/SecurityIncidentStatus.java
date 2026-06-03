package com.mannschaft.app.securityincident;

/**
 * セキュリティインシデントの対応ステータス。
 */
public enum SecurityIncidentStatus {
    /** 未対応 */
    OPEN,
    /** 調査中 */
    INVESTIGATING,
    /** 封じ込め済み */
    CONTAINED,
    /** 解決済み */
    CLOSED
}
