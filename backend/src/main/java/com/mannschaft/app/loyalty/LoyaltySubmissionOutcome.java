package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;

/** SELF_SUBMITの二つの成功receiptを区別する内部結果。 */
public record LoyaltySubmissionOutcome(TransactionResponse transaction, LoyaltyAwardRequestEntity request) {
}
