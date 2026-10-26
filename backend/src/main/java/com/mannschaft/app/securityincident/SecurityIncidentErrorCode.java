package com.mannschaft.app.securityincident;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セキュリティインシデント管理ドメインのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SecurityIncidentErrorCode implements ErrorCode {

    /** セキュリティインシデントが見つからない */
    SECURITY_INCIDENT_NOT_FOUND("SEC_INCIDENT_001", "セキュリティインシデントが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
