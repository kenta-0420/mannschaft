package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * F20.1: シスアド 手動付与リクエスト（契約行を作って発行・設計書 02 §4）。
 *
 * <p>処理は §3.1 の契約作成と同一（{@code created_by}=シスアド）。ベータ検証・サポート対応用。
 * <b>REVENUE イベントは発火しない</b>（運営操作＝団体の商用行動ではない・§7.0 AC-26）。
 *
 * @param scopeKind    USER / TEAM / ORG
 * @param scopeId      users.id / teams.id / organizations.id
 * @param contractKind PLAN / ADDON
 * @param planKey      PLAN 時必須
 * @param featureKey   ADDON 時必須
 * @param note         付与理由（監査用・任意）
 */
@Schema(name = "BillingManualGrantRequest", description = "F20.1 シスアド 手動付与")
public record ManualGrantRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TEAM")
        @NotBlank
        String scopeKind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "123")
        Long scopeId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PLAN")
        @NotBlank
        String contractKind,

        @Schema(nullable = true, example = "FULL")
        String planKey,

        @Schema(nullable = true, example = "ads.hide")
        String featureKey,

        @Schema(nullable = true, example = "ベータ検証のため付与")
        String note) {
}
