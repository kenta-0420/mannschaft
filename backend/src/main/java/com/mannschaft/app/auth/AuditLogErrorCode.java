package com.mannschaft.app.auth;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 監査ログ機能固有のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum AuditLogErrorCode implements ErrorCode {

    /** from > to の日付範囲不正 */
    INVALID_DATE_RANGE("AUDIT_001", "開始日時は終了日時より前に設定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
