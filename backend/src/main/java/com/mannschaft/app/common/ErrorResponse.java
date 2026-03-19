package com.mannschaft.app.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * エラーレスポンス。
 * <pre>
 * { "error": { "code": "AUTH_001", "message": "...", "fieldErrors": [...] } }
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public class ErrorResponse {

    private final ErrorDetail error;

    /**
     * ErrorCode からフィールドエラーなしの ErrorResponse を生成する。
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), List.of()));
    }

    /**
     * ErrorCode とフィールドエラー一覧から ErrorResponse を生成する。
     */
    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(
                new ErrorDetail(errorCode.getCode(), errorCode.getMessage(), fieldErrors));
    }

    /**
     * エラー詳細。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final List<FieldError> fieldErrors;
    }

    /**
     * フィールド単位のバリデーションエラー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FieldError {
        private final String field;
        private final String message;
    }
}
