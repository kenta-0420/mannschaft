package com.mannschaft.app.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * グローバル例外ハンドラー。
 * 全ての例外を統一された ErrorResponse 形式に変換する。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * ErrorCode ごとの HttpStatus 個別マッピング。
     * Severity ベースのデフォルトマッピングを上書きしたい場合にここへ追加する。
     */
    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.of(
            CommonErrorCode.COMMON_002.getCode(), HttpStatus.FORBIDDEN,
            CommonErrorCode.COMMON_003.getCode(), HttpStatus.CONFLICT,
            "AD_006", HttpStatus.CONFLICT,
            "AD_007", HttpStatus.CONFLICT,
            "AD_010", HttpStatus.FORBIDDEN
    );

    /**
     * 業務例外ハンドラー。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("BusinessException: code={}, message={}", errorCode.getCode(), errorCode.getMessage());
        ErrorResponse body = ex.getFieldErrors().isEmpty()
                ? ErrorResponse.of(errorCode)
                : ErrorResponse.of(errorCode, ex.getFieldErrors());
        return ResponseEntity
                .status(resolveHttpStatus(errorCode))
                .body(body);
    }

    /**
     * Bean Validation エラー（@Valid 付きリクエストボディ）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        log.warn("Validation failed: {} field error(s)", fieldErrors.size());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001, fieldErrors));
    }

    /**
     * リクエストボディのパースエラー（JSON 形式不正など）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        log.warn("Message not readable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * パスパラメータ・リクエストパラメータの型変換エラー。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: parameter={}, value={}", ex.getName(), ex.getValue());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * 必須リクエストパラメータの欠落。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex) {
        log.warn("Missing parameter: {}", ex.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * 楽観ロック競合。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_003));
    }

    /**
     * その他の予期しない例外。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * ErrorCode から HttpStatus を解決する。
     * 個別マッピング（ERROR_CODE_STATUS_MAP）が存在すればそちらを優先し、
     * なければ Severity に基づくデフォルトマッピングを返す。
     *
     * @param errorCode エラーコード
     * @return 対応する HttpStatus
     */
    protected HttpStatus resolveHttpStatus(ErrorCode errorCode) {
        // 個別マッピングを優先
        HttpStatus mapped = ERROR_CODE_STATUS_MAP.get(errorCode.getCode());
        if (mapped != null) {
            return mapped;
        }

        // Severity ベースのデフォルトマッピング
        return switch (errorCode.getSeverity()) {
            case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case WARN -> HttpStatus.BAD_REQUEST;
            case INFO -> HttpStatus.OK;
        };
    }
}
