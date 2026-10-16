package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardPreviewRequest")
public record SelfAwardPreviewRequest(
        @NotBlank @Size(max = 512) String locationToken,
        @NotNull UUID ruleId) {
}
