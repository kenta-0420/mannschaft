package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/** 月境界・quote/preview 競合の型付き details を保持する業務例外。 */
public class BillingConflictException extends BusinessException {
    private final BillingConflictDetails details;

    public BillingConflictException(ErrorCode errorCode, BillingConflictDetails details) {
        super(errorCode);
        this.details = details;
    }

    public BillingConflictDetails getDetails() {
        return details;
    }

    public record BillingConflictDetails(
            Reason reason, Instant availableAt, UUID quoteId, String pendingChangeStatus) {

        /**
         * 既存呼び出し元（月境界・quote/preview 競合）向けの後方互換コンストラクタ。
         * {@code pendingChangeStatus} を持たない検体は {@code null}（PR6b-1 AC-101 以前と同じ形）。
         */
        public BillingConflictDetails(Reason reason, Instant availableAt, UUID quoteId) {
            this(reason, availableAt, quoteId, null);
        }
    }

    public enum Reason {
        MONTH_BOUNDARY,
        QUOTE_STALE,
        QUOTE_EXPIRED,
        PREVIEW_EXPIRED,
        CHANGE_CONFLICT
    }
}
