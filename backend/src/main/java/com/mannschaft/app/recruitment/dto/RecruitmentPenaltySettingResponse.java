package com.mannschaft.app.recruitment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F03.11 Phase 5b: ペナルティ設定レスポンス DTO。
 */
@Getter
@AllArgsConstructor
public class RecruitmentPenaltySettingResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    /**
     * 有効フラグ。JSON キーは画面・API 契約どおり {@code isEnabled}。
     * Lombok の getter は {@code isEnabled()} となり Jackson は暗黙名を {@code enabled} と解釈するため、
     * フィールド名を {@code enabled} に揃えたうえで {@link JsonProperty} で JSON 名を明示する。
     */
    @JsonProperty("isEnabled")
    private final boolean enabled;
    private final int thresholdCount;
    private final int thresholdPeriodDays;
    private final int penaltyDurationDays;
    private final String applyScope;
    private final boolean autoNoShowDetection;
    private final int disputeAllowedDays;
    private final String createdAt;
    private final String updatedAt;
}
