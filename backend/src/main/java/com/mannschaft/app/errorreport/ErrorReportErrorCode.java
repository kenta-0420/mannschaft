package com.mannschaft.app.errorreport;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.5 エラーレポートのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorReportErrorCode implements ErrorCode {

    /** エラーレポートが見つからない */
    ERROR_REPORT_NOT_FOUND("ERROR_REPORT_001", "エラーレポートが見つかりません", Severity.WARN),

    /** このエラーレポートは既に解決済み */
    ERROR_REPORT_ALREADY_RESOLVED("ERROR_REPORT_002", "このエラーレポートは既に解決済みです", Severity.WARN),

    /** 一括更新の上限超過 */
    ERROR_REPORT_BULK_LIMIT_EXCEEDED("ERROR_REPORT_003", "一括更新の上限（100件）を超えています", Severity.WARN),

    /** 無効なステータス遷移 */
    ERROR_REPORT_INVALID_STATUS_TRANSITION("ERROR_REPORT_004", "無効なステータス遷移です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
