package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モジュールの有料プラン要否更新リクエスト（SYSTEM_ADMIN用）。
 * {@code PATCH /api/v1/system-admin/modules/{id}/paid-plan} のボディ。
 */
@Getter
@RequiredArgsConstructor
public class UpdateModulePaidPlanRequest {

    /** 有料プランを必須とするか。 */
    @NotNull
    private final Boolean requiresPaidPlan;
}
