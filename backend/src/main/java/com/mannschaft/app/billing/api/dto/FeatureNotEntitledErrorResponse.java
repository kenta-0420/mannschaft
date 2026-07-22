package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.EntitlementNotEntitledDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * F20.1: {@code FEATURE_NOT_ENTITLED}（402・ENTITLEMENT_003）専用のエラー応答。
 *
 * <p>既存の共通 {@code com.mannschaft.app.common.ErrorResponse} / {@code ErrorResponse.ErrorDetail} は
 * {@code code}/{@code message}/{@code fieldErrors} の 3 フィールドのみで details を持てない
 * （共通クラス不変・バイト不変が要件のため変更しない）。本クラスは同じ envelope 形状
 * （{@code error.code}/{@code error.message}/{@code error.fieldErrors}）を維持しつつ、
 * {@code error.details} に購入導線情報を追加する専用レスポンス（案B）。</p>
 *
 * <pre>
 * { "error": { "code": "ENTITLEMENT_003", "message": "...", "fieldErrors": [], "details": {...} } }
 * </pre>
 */
@Getter
@Schema(name = "BillingFeatureNotEntitledErrorResponse", description = "F20.1 402 応答（details 付き）")
public class FeatureNotEntitledErrorResponse {

    private final ErrorDetail error;

    public FeatureNotEntitledErrorResponse(String code, String message, EntitlementNotEntitledDetails details) {
        this.error = new ErrorDetail(code, message, List.of(), details);
    }

    @Getter
    @RequiredArgsConstructor
    public static class ErrorDetail {
        @Schema(description = "エラーコード", example = "ENTITLEMENT_003")
        private final String code;

        @Schema(description = "エラーメッセージ")
        private final String message;

        @Schema(description = "フィールドエラー一覧（本エラーでは常に空）")
        private final List<com.mannschaft.app.common.ErrorResponse.FieldError> fieldErrors;

        @Schema(description = "購入導線情報")
        private final EntitlementNotEntitledDetails details;
    }
}
