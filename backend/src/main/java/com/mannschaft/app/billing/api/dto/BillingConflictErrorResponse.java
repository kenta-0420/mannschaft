package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.api.BillingConflictException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Billing Center PR6b-1: {@link BillingConflictException}（月境界・preview/quote 競合）専用のエラー応答
 * （金型: {@code FeatureNotEntitledErrorResponse}）。
 *
 * <p>共通の {@code com.mannschaft.app.common.ErrorResponse} は details を持てないため、同じ envelope
 * 形状（{@code error.code}/{@code error.message}/{@code error.fieldErrors}）を維持しつつ
 * {@code error.details} に {@code reason}/{@code availableAt}/{@code quoteId} を追加する。</p>
 *
 * <pre>
 * { "error": { "code": "ENTITLEMENT_022", "message": "...", "fieldErrors": [],
 *              "details": {"reason":"MONTH_BOUNDARY","availableAt":"...","quoteId":null} } }
 * </pre>
 */
@Getter
@Schema(name = "BillingConflictErrorResponse", description = "PR6b-1 409 応答（details 付き）")
public class BillingConflictErrorResponse {

    private final ErrorDetail error;

    public BillingConflictErrorResponse(
            String code, String message, BillingConflictException.BillingConflictDetails details) {
        this.error = new ErrorDetail(code, message, List.of(), details);
    }

    @Getter
    @RequiredArgsConstructor
    public static class ErrorDetail {
        @Schema(description = "エラーコード", example = "ENTITLEMENT_021")
        private final String code;

        @Schema(description = "エラーメッセージ")
        private final String message;

        @Schema(description = "フィールドエラー一覧（本エラーでは常に空）")
        private final List<com.mannschaft.app.common.ErrorResponse.FieldError> fieldErrors;

        @Schema(description = "競合の詳細（理由・再試行可能時刻・関連 quote/preview ID）")
        private final BillingConflictException.BillingConflictDetails details;
    }
}
