package com.mannschaft.app.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 業務例外。ErrorCode を保持し、GlobalExceptionHandler で適切な HTTP レスポンスに変換される。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;

    /**
     * このスロー箇所固有の HTTP ステータス上書き（任意）。
     *
     * <p>通常は {@link GlobalExceptionHandler} が {@link ErrorCode} から一意にステータスを解決するが、
     * <b>同一エラーコードが文脈によって異なるステータスを取る</b>ケース（例: F04.12 の {@code ROLE_009} は
     * 宛先照合 IDOR で 403、発行時の特権ロール指定で 422）では、コード→ステータスの静的マップが 1:1 で
     * 表現できない。そのためスロー箇所でステータスを明示できるようにする。{@code null} の場合は従来どおり
     * コードベースのマッピングに委ねる。</p>
     */
    private final HttpStatus httpStatusOverride;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.httpStatusOverride = null;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.httpStatusOverride = null;
    }

    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors != null ? fieldErrors : List.of();
        this.httpStatusOverride = null;
    }

    /**
     * スロー箇所固有の HTTP ステータスを明示する版（設計書 F04.12 §6 の {@code ROLE_009} など、
     * 同一コードが文脈により異なるステータスを取るケース用）。
     *
     * @param errorCode          エラーコード
     * @param httpStatusOverride このスロー箇所で返すべき HTTP ステータス
     */
    public BusinessException(ErrorCode errorCode, HttpStatus httpStatusOverride) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.httpStatusOverride = httpStatusOverride;
    }
}
