package com.mannschaft.app.repairplan.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 修繕計画項目レスポンス DTO（F08.8 Phase 1 案5）。
 *
 * <p>{@code id} は UUID 文字列で返す。{@code version} はクライアント側で
 * If-Match に格納して PATCH/DELETE に渡す楽観ロック値。</p>
 */
@Getter
@Builder
public class RepairPlanItemDto {

    private final String id;
    private final Long organizationId;
    private final String scopeType;
    private final Long scopeId;
    private final UUID templateId;
    private final String category;
    private final String title;
    private final String description;
    private final Integer plannedYear;
    private final Integer plannedMonth;
    private final Long estimatedAmount;
    private final Integer cpiInflationBasisYear;
    private final String status;
    private final Long linkedWorkPackageId;
    private final String tags;
    private final Long createdBy;
    private final Long updatedBy;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
