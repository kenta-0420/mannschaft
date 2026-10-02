package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * F20.1: プラン変更リクエスト（設計書 02 §3.3）。
 *
 * @param planKey 変更後のプランキー（同一 planKey への変更は ENTITLEMENT_006 409）
 */
@Schema(name = "BillingChangePlanRequest", description = "F20.1 プラン変更リクエスト")
public record ChangePlanRequest(

        @Schema(description = "変更後のプランキー", example = "FULL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String planKey) {
}
