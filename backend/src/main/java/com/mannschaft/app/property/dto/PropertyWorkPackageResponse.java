package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物件履歴パッケージ詳細レスポンス DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §4 レスポンス例（パッケージ詳細）に対応。
 * 金額系フィールド（{@code estimatedAmount}/{@code contractAmount}/{@code actualAmount}）と
 * {@code vendorNameSnapshot}/業者連絡先 は、呼び出し側が
 * {@link com.mannschaft.app.property.service.PropertyWorkPackageMaskingService} の
 * {@link MaskedView} を渡してマスキング後の値で生成する想定。
 * {@link #permissions} の {@code canViewAmount} で UI が金額表示有無を判定する。</p>
 */
public record PropertyWorkPackageResponse(
        Long id,
        String scopeType,
        Long scopeId,
        Long dwellingUnitId,
        WorkType workType,
        String category,
        String title,
        String description,
        Long incidentId,
        LocalDate incidentDate,
        String incidentNarrative,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        Long vendorId,
        String vendorNameSnapshot,
        Long estimatedAmount,
        Long contractAmount,
        Long actualAmount,
        String currency,
        Long budgetTransactionId,
        Long timelinePostId,
        LocalDate warrantyUntil,
        Boolean isDisclosable,
        WorkPackageVisibility visibility,
        WorkPackageStatus status,
        Integer attachmentCount,
        Integer commentCount,
        List<String> tags,
        List<PropertyWorkDocumentResponse> documents,
        Long createdBy,
        Long updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version,
        PermissionsResponse permissions) {

    /** 操作可否フラグ（フロントの編集ボタン等の表示制御に利用）。 */
    public record PermissionsResponse(
            boolean canEdit,
            boolean canDelete,
            boolean canViewAmount) {
    }

    /**
     * Entity + マスキング結果 + tags（デシリアライズ済み）+ documents から DTO を生成する。
     *
     * @param entity      パッケージ Entity
     * @param masked      マスキング結果（金額・vendor.* のマスク後値を含む）
     * @param tags        deserialize 済みタグリスト
     * @param documents   関連文書 DTO（一覧時は {@code null} 可）
     * @param canEdit     編集可否
     * @param canDelete   削除可否
     */
    public static PropertyWorkPackageResponse from(
            PropertyWorkPackageEntity entity,
            MaskedView masked,
            List<String> tags,
            List<PropertyWorkDocumentResponse> documents,
            boolean canEdit,
            boolean canDelete) {
        boolean canViewAmount = masked != null && masked.canViewAmount();
        Long estimated = masked != null ? masked.estimatedAmount() : entity.getEstimatedAmount();
        Long contract = masked != null ? masked.contractAmount() : entity.getContractAmount();
        Long actual = masked != null ? masked.actualAmount() : entity.getActualAmount();

        return new PropertyWorkPackageResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getDwellingUnitId(),
                entity.getWorkType(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getIncidentId(),
                entity.getIncidentDate(),
                entity.getIncidentNarrative(),
                entity.getPlannedStartDate(),
                entity.getPlannedEndDate(),
                entity.getActualStartDate(),
                entity.getActualEndDate(),
                entity.getVendorId(),
                entity.getVendorNameSnapshot(),
                estimated,
                contract,
                actual,
                entity.getCurrency(),
                entity.getBudgetTransactionId(),
                entity.getTimelinePostId(),
                entity.getWarrantyUntil(),
                entity.getIsDisclosable(),
                entity.getVisibility(),
                entity.getStatus(),
                entity.getAttachmentCount(),
                entity.getCommentCount(),
                tags,
                documents,
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion(),
                new PermissionsResponse(canEdit, canDelete, canViewAmount));
    }
}
