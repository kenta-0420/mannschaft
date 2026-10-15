package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * 修繕計画項目 作成リクエスト DTO（F08.8 Phase 1 案5）。
 *
 * <ul>
 *   <li>{@code title} — 必須、200 文字以内</li>
 *   <li>{@code category} — 必須（共用部 / 給排水 / 電気 / 屋上防水 など、自由文字列だが空不可）</li>
 *   <li>{@code plannedYear} — 2020〜2100</li>
 *   <li>{@code estimatedAmount} — 0 以上（円）</li>
 *   <li>{@code status} — PLANNED / IN_PROGRESS / COMPLETED / DEFERRED / CANCELED（省略時 PLANNED）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class CreateRepairPlanItemRequest {

    private final UUID templateId;

    @NotBlank
    @Size(max = 60)
    private final String category;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @NotNull
    @Min(2020)
    @Max(2100)
    private final Integer plannedYear;

    @Min(1)
    @Max(12)
    private final Integer plannedMonth;

    @NotNull
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
