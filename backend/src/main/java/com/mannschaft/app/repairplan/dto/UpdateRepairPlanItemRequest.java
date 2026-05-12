package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * 修繕計画項目 更新リクエスト DTO（F08.8 Phase 1 案5）。
 *
 * <p>PATCH 半適用: null フィールドは未変更扱い。{@code title} のみ提示時は空白不可。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateRepairPlanItemRequest {

    private final UUID templateId;

    @Size(max = 60)
    private final String category;

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Min(2020)
    @Max(2100)
    private final Integer plannedYear;

    @Min(1)
    @Max(12)
    private final Integer plannedMonth;

    @Min(0)
    private final Long estimatedAmount;

    @Min(2000)
    @Max(2100)
    private final Integer cpiInflationBasisYear;

    @Pattern(regexp = "PLANNED|IN_PROGRESS|COMPLETED|DEFERRED|CANCELED",
            message = "status は PLANNED / IN_PROGRESS / COMPLETED / DEFERRED / CANCELED のいずれかである必要があります")
    private final String status;

    private final Long linkedWorkPackageId;

    private final String tags;
}
