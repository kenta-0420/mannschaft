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

    /**
     * F20.1 実決済（D-4）: 決済フロー（価格設定済み）で作成した契約の Stripe Checkout URL。
     * 無償フロー（価格 NULL・即 ACTIVE）や解約/変更レスポンスでは null。FE はこの URL へ遷移して決済させる。
     */
    @Schema(description = "決済フロー時の Stripe Checkout URL（無償フロー・解約時は null）", nullable = true)
    private final String checkoutUrl;

    /**
     * F20.1 実決済（D-3）: 決済フロー契約の現サイクル終了（有償解約の「いつまで使えるか」・期末失効時刻）。
     * 無償契約や PENDING/未決済では null。
     */
    @Schema(description = "現サイクル終了（有償解約の利用可能期限・ISO-8601）", nullable = true)
    private final LocalDateTime currentPeriodEnd;
}
