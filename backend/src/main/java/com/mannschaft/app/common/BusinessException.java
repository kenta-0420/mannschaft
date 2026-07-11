package com.mannschaft.app.common;

import lombok.Getter;

import java.util.List;

/**
 * 業務例外。ErrorCode を保持し、GlobalExceptionHandler で適切な HTTP レスポンスに変換される。
 *
 * <p>{@code details} は F20.1 で additive 追加した任意ペイロード（現状は
 * {@code ENTITLEMENT_003}(402) の購入導線情報のみ）。{@link #withDetails(ErrorCode, Object)} で
 * 生成し、{@code GlobalExceptionHandler} が {@code ErrorResponse.ErrorDetail.details} へ透過する。
 * 既存コンストラクタは details=null のままで挙動不変（後方互換）。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;

    /**
     * エラーコード固有の追加情報（任意・F20.1）。null なら応答 JSON に details は現れない。
     * 例外シリアライズには含めない（transient・API 応答生成のみに使う一時運搬）。
     */
    private final transient Object details;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors != null ? fieldErrors : List.of();
        this.details = null;
    }

    /**
     * details 運搬用の内部コンストラクタ（生成は {@link #withDetails(ErrorCode, Object)} 経由・
     * private のため既存の public overload の解決に影響しない）。
     */
    private BusinessException(ErrorCode errorCode, Object details) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.details = details;
    }

    /**
     * details 付き業務例外を生成する（F20.1・{@code ENTITLEMENT_003} の購入導線等）。
     *
     * <p>コンストラクタ overload ではなく static factory にした理由: 既存の
     * {@code BusinessException(ErrorCode, List<FieldError>)} と {@code (ErrorCode, Object)} が
     * 並ぶと呼び出し側で型があいまいになりやすい（List も Object であるため）。名前で意図を
     * 明示し、既存呼び出しへの影響をゼロにする。</p>
     *
     * @param errorCode エラーコード
     * @param details   エラーコード固有の追加情報（Jackson で直列化可能な DTO/record）
     * @return details を保持した業務例外
     */
    public static BusinessException withDetails(ErrorCode errorCode, Object details) {
        return new BusinessException(errorCode, details);
    }
}
