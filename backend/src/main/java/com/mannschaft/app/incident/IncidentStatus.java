package com.mannschaft.app.incident;

/**
 * インシデントステータス。
 */
public enum IncidentStatus {

    /** 報告済み */
    REPORTED,

    /** 確認済み */
    ACKNOWLEDGED,

    /** 対応中 */
    IN_PROGRESS,

    /** 解決済み（担当者側クローズ） */
    RESOLVED,

    /** 確認済み（報告者側確認） */
    CONFIRMED,

    /** 再オープン */
    REOPENED,

    /** クローズ */
    CLOSED
}
