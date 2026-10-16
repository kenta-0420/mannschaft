package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.loyalty.SelfAwardOutcome;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardResult")
public record SelfAwardResult(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SelfAwardOutcome outcome,
        @Schema(types = {"object", "null"}, nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        TransactionResponse transaction,
        @Schema(types = {"object", "null"}, nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        AwardRequestResponse request) {
}
