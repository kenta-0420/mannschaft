package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;

import java.time.LocalDate;

/**
 * 物件履歴パッケージ一覧用 軽量レスポンス DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §4 一覧 API（{@code GET /property-history}）/ ガント / タイムライン用。
 * 詳細用フィールド（description / incident_narrative / documents 等）は含まない。
 * {@code actualAmount} は閲覧者ロールによってマスク後 {@code null} になる場合あり。</p>
 */
public record PropertyWorkPackageSummaryResponse(
        Long id,
        WorkType workType,
        String category,
        String title,
        LocalDate actualEndDate,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        Long vendorId,
        String vendorNameSnapshot,
        Long actualAmount,
        WorkPackageStatus status,
        Boolean canViewAmount) {

    public static PropertyWorkPackageSummaryResponse from(
            PropertyWorkPackageEntity entity, MaskedView masked) {
        boolean canViewAmount = masked != null && masked.canViewAmount();
        Long actual = masked != null ? masked.actualAmount() : entity.getActualAmount();
        return new PropertyWorkPackageSummaryResponse(
                entity.getId(),
                entity.getWorkType(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getActualEndDate(),
                entity.getPlannedStartDate(),
                entity.getPlannedEndDate(),
                entity.getVendorId(),
                entity.getVendorNameSnapshot(),
                actual,
                entity.getStatus(),
                canViewAmount);
    }
}
