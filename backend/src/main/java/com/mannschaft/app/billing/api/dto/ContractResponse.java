package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F20.1: 契約操作（作成/解約/変更）のレスポンス（設計書 02 §3.1）。
 */
@Getter
@Builder
@Schema(name = "BillingContractResponse", description = "F20.1 契約操作のレスポンス")
public class ContractResponse {

    @Schema(description = "契約 ID（UUID）")
    private final String contractId;

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;

    @Schema(description = "契約種別（PLAN / ADDON）", example = "PLAN")
    private final String contractKind;

    @Schema(description = "プランキー（ADDON 時 null）", nullable = true, example = "FULL")
    private final String planKey;

    @Schema(description = "機能キー（PLAN 時 null）", nullable = true, example = "ads.hide")
    private final String featureKey;

    @Schema(description = "契約ステータス", example = "ACTIVE")
    private final String status;

    @Schema(description = "契約時アクティブ人数スナップショット（USER 時 null）", nullable = true, example = "34")
    private final Integer memberCountSnapshot;

    @Schema(description = "契約時バンド番号スナップショット", nullable = true, example = "2")
    private final Short bandNoSnapshot;

    @Schema(description = "契約時単価スナップショット（ベータ中 null＝無償）", nullable = true)
    private final Integer priceJpySnapshot;

    @Schema(description = "契約日時（ISO-8601）")
    private final LocalDateTime contractedAt;

    @Schema(description = "この契約で発行された機能キー集合")
    private final List<String> grantedFeatureKeys;
}
