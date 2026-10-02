package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F20.1: 利用できる 1 機能（設計書 02 §2.2）。
 *
 * <p>{@code sourceKind} は PLAN / ADDON / BETA_GRANT / FREE / NONPROFIT_FREE。
 * virtual エントリ（FREE・NONPROFIT_FREE）の {@code validUntil} は常に null（無期限）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingEntitledFeature", description = "F20.1 利用できる 1 機能")
public class EntitledFeature {

    @Schema(description = "機能キー", example = "ads.hide")
    private final String featureKey;

    @Schema(description = "由来（PLAN / ADDON / BETA_GRANT / FREE / NONPROFIT_FREE）", example = "PLAN")
    private final String sourceKind;

    @Schema(description = "有効終了（ISO-8601）。無期限・virtual は null", nullable = true)
    private final LocalDateTime validUntil;
}
