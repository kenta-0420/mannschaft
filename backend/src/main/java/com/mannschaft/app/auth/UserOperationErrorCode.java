package com.mannschaft.app.auth;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 本人操作の受付拒否。内部容量や原情報を応答へ含めない。 */
@Getter
@RequiredArgsConstructor
public enum UserOperationErrorCode implements ErrorCode {
    /** HTTP 503。 */
    UNAVAILABLE("AUTHOPERATION_001", "本人操作を受け付けられません。時間をおいて再度お試しください", Severity.WARN),

    /** HTTP 403。不存在・凍結・退会を同じ応答へまとめる。 */
    NOT_ALLOWED("AUTHOPERATION_002", "本人操作を利用できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
