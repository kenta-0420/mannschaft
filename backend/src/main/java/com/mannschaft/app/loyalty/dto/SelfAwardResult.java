package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardOutcome;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardResult")
public record SelfAwardResult(
        SelfAwardOutcome outcome,
        TransactionResponse transaction,
        AwardRequestResponse request) {
}
