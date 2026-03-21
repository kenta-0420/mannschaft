package com.mannschaft.app.common;

import lombok.Getter;

import java.util.List;

/**
 * 業務例外。ErrorCode を保持し、GlobalExceptionHandler で適切な HTTP レスポンスに変換される。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors != null ? fieldErrors : List.of();
    }
}
