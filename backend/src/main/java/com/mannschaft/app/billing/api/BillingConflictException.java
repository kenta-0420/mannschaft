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

    public record BillingConflictDetails(Reason reason, Instant availableAt, UUID quoteId) { }

    public enum Reason {
        MONTH_BOUNDARY,
        QUOTE_STALE,
        QUOTE_EXPIRED,
        PREVIEW_EXPIRED,
        CHANGE_CONFLICT
    }
}
