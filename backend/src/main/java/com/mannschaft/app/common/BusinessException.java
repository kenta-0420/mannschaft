package com.mannschaft.app.common;

import lombok.Getter;

/**
 * 業務例外。ErrorCode を保持し、GlobalExceptionHandler で適切な HTTP レスポンスに変換される。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
