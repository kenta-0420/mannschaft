package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;

/** 終端化のcommitを完了してからFacadeが409へ変換する結果。 */
public record LoyaltyRequestActionOutcome(LoyaltyAwardRequestEntity request,
        TransactionResponse transaction, LoyaltyErrorCode errorCode) {
}
