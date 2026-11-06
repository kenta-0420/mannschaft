package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11 Phase 5b: ペナルティ設定 UPSERT リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class UpsertPenaltySettingRequest {

    /**
     * 有効フラグ。JSON キーは画面・API 契約どおり {@code isEnabled}。
     * Lombok の getter は {@code isEnabled()} となり Jackson は暗黙名を {@code enabled} と解釈するため、
     * フィールド名を {@code enabled} に揃えたうえで {@link JsonProperty} で JSON 名を明示する。
     */
    @JsonProperty("isEnabled")
    private boolean enabled = true;

    @Min(1)
    @Max(10)
    private int thresholdCount = 3;

    @Min(1)
    @Max(365)
    private int thresholdPeriodDays = 180;

    @Min(1)
    @Max(365)
    private int penaltyDurationDays = 30;

    private PenaltyApplyScope applyScope = PenaltyApplyScope.THIS_SCOPE_ONLY;

    private boolean autoNoShowDetection = false;

    @Min(0)
    @Max(30)
    private int disputeAllowedDays = 30;
}
