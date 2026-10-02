package com.mannschaft.app.common.duplicatename;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * CMP-260901-1538 柱③-A: {@code DUPNAME_001}（409）専用のエラー応答。
 *
 * <p>金型: {@code com.mannschaft.app.billing.api.dto.FeatureNotEntitledErrorResponse}。共通の
 * {@code ErrorResponse} は details を持てないため、同じ envelope 形状を維持しつつ
 * {@code error.details} に候補一覧・fingerprint を追加する専用レスポンス。</p>
 *
 * <pre>
 * { "error": { "code": "DUPNAME_001", "message": "...", "fieldErrors": [], "details": {...} } }
 * </pre>
 */
@Getter
@Schema(name = "DuplicateNameConfirmationErrorResponse", description = "柱③-A 409 応答（候補一覧・fingerprint 付き）")
public class DuplicateNameConfirmationErrorResponse {

    private final ErrorDetail error;

    public DuplicateNameConfirmationErrorResponse(String code, String message, DuplicateNameConfirmationDetails details) {
        this.error = new ErrorDetail(code, message, List.of(), details);
    }

    @Getter
    @RequiredArgsConstructor
    public static class ErrorDetail {
        @Schema(description = "エラーコード", example = "DUPNAME_001")
        private final String code;

        @Schema(description = "エラーメッセージ")
        private final String message;

        @Schema(description = "フィールドエラー一覧（本エラーでは常に空）")
        private final List<com.mannschaft.app.common.ErrorResponse.FieldError> fieldErrors;

        @Schema(description = "同名確認フローの詳細（候補一覧・fingerprint）")
        private final DuplicateNameConfirmationDetails details;
    }
}
