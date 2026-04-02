package com.mannschaft.app.errorreport;

/**
 * F12.5 エラーレポートの重要度。
 * ordinal() の順序が LOW < MEDIUM < HIGH < CRITICAL となるよう定義する。
 * severity 昇格判定で ordinal() を比較するため、順序を変更しないこと。
 */
public enum ErrorReportSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}
