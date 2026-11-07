package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;

/** 終端化を伴うエラーもcommitしてからFacadeがHTTPへ変換する。 */
public record LoyaltySubmissionOutcome(TransactionResponse transaction, LoyaltyAwardRequestEntity request,
        LoyaltyErrorCode errorCode) {
    public LoyaltySubmissionOutcome(TransactionResponse transaction, LoyaltyAwardRequestEntity request) {
        this(transaction, request, null);
    }
}
