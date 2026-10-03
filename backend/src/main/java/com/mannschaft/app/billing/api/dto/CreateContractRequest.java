package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * F20.1: 契約作成リクエスト（PLAN / ADDON・設計書 02 §3.1）。
 *
 * <p><b>record ゆえ Jackson の {@code @JsonCreator} 相当を自動獲得</b>する
 * （memory {@code feedback_dto_all_final_multi_constructor_jackson_no_creators} の回避）。
 * {@code contractKind} が PLAN/ADDON 以外・欠落時の詳細検証は Service 層
 * （{@code ENTITLEMENT_014} / {@code ENTITLEMENT_001} / {@code ENTITLEMENT_002}）で行う。</p>
 *
 * @param contractKind PLAN / ADDON
 * @param planKey      PLAN 時必須（plans.enabled=true に実在）
 * @param featureKey   ADDON 時必須（feature_catalog.enabled=true かつ addon_available=true）
 */
@Schema(name = "BillingCreateContractRequest", description = "F20.1 契約作成リクエスト")
public record CreateContractRequest(

        @Schema(description = "契約種別（PLAN / ADDON）", example = "PLAN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String contractKind,

        @Schema(description = "プランキー（PLAN 時必須）", example = "FULL", nullable = true)
        String planKey,

        @Schema(description = "機能キー（ADDON 時必須）", example = "ads.hide", nullable = true)
        String featureKey) {
}
