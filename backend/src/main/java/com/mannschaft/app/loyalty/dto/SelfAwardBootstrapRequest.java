package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardBootstrapRequest")
public record SelfAwardBootstrapRequest(
        @NotBlank @Size(max = 512) String locationToken) {
}
