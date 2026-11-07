package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyRequestActionRequest")
public record RequestActionRequest(
        @NotNull @PositiveOrZero Long expectedRequestVersion) {
}
