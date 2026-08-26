package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1: プランカタログのレスポンス（設計書 02 §2.1）。
 *
 * <p>{@code GET /api/v1/billing/plans} の封筒 {@code ApiResponse<PlanCatalogResponse>}。
 * {@code enabled=false} のプラン・機能は含めない（Service 層で除外）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingPlanCatalogResponse", description = "F20.1 プランカタログ（利用者向け・読み取り）")
public class PlanCatalogResponse {

    @Schema(description = "提示プラン一覧（sort_order 昇順・enabled のみ）")
    private final List<PlanItem> plans;
}
