package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** {@code GET /api/v1/public/billing/plans} の公開カタログ応答。 */
@Getter
@Builder
@Schema(name = "PublicBillingCatalogResponse", description = "スコープ種別ごとの公開プラン・アドオン価格")
public class PublicBillingCatalogResponse {

    @Schema(description = "価格を表示するスコープ種別", allowableValues = {"USER", "TEAM", "ORG"})
    private final String scopeKind;

    private final List<PublicPlan> plans;
    private final List<PublicAddon> addons;
}
