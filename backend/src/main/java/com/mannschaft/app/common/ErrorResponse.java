package com.mannschaft.app.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * エラーレスポンス。
 * <pre>
 * { "error": { "code": "AUTH_001", "message": "...", "fieldErrors": [...], "details": {...}? } }
 * </pre>
 *
 * <p>{@code details} は F20.1 で additive 追加した任意ペイロード（現状は
 * {@code ENTITLEMENT_003}(402) の購入導線情報のみ・設計書 02 §1.2）。null の場合は
 * {@code @JsonInclude(NON_NULL)} により JSON から完全に省略されるため、既存エラー応答の
 * 後方互換は保たれる（details を使わないエラーコードの JSON は従来と不変）。</p>
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
    public static class ErrorDetail {
        private final String code;
        private final String message;
        private final List<FieldError> fieldErrors;

        /**
         * エラーコード固有の追加情報（任意・F20.1 で additive 追加）。
         *
         * <p>現状の用途は {@code ENTITLEMENT_003}(402) の購入導線情報
         * （{@code EntitlementNotEntitledDetails}: featureKey / addonAvailable / addonPriceJpy /
         * plansContaining・設計書 02 §1.2）。null なら JSON から省略される（後方互換）。
         * 型はエラーコードごとに異なり得るため {@link Object} で運び、OpenAPI 上は自由形式
         * オブジェクトとして表現する（各コードの具象スキーマは components/schemas に別名で登録）。</p>
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(nullable = true, description = "エラーコード固有の追加情報（例: ENTITLEMENT_003 の購入導線 "
                + "EntitlementNotEntitledDetails）。null の場合は JSON から省略される。")
        private final Object details;

        /** 既存互換コンストラクタ（details なし・従来 JSON 不変）。 */
        public ErrorDetail(String code, String message, List<FieldError> fieldErrors) {
            this(code, message, fieldErrors, null);
        }

        /** details 付きコンストラクタ（F20.1・ENTITLEMENT_003 購入導線等）。 */
        public ErrorDetail(String code, String message, List<FieldError> fieldErrors, Object details) {
            this.code = code;
            this.message = message;
            this.fieldErrors = fieldErrors;
            this.details = details;
        }
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
