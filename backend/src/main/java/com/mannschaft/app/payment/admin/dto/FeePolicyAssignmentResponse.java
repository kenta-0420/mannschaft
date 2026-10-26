package com.mannschaft.app.payment.admin.dto;

import com.mannschaft.app.payment.FeePolicyAssignmentEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン割当（{@code fee_policy_assignments}）のシスアド管理レスポンス（設計書 02 §11）。
 *
 * <p>casing camelCase 1:1。{@code subKey} は source_kind 既定割当のとき null。{@code organizationId} は R2 では常に null
 * （テナント別上書きは将来拡張・§3.5.3）。</p>
 *
 * @param id             割当 ID（UUIDv7）
 * @param sourceKind     解決キー（RECRUITMENT/MEMBERSHIP/TOURNAMENT/JOBMATCHING/FLEAMARKET）
 * @param subKey         細分キー（助っ人＝recruitment_category 値 等・null＝source_kind 既定）
 * @param policyKey      適用する手数料パターンの自然キー
 * @param enabled        有効フラグ
 * @param createdAt      作成日時
 * @param updatedAt      更新日時
 */
@Builder
public record FeePolicyAssignmentResponse(
        UUID id,
        String sourceKind,
        String subKey,
        String policyKey,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * エンティティからレスポンスを生成する。
     *
     * @param entity 割当エンティティ
     * @return レスポンス
     */
    public static FeePolicyAssignmentResponse from(FeePolicyAssignmentEntity entity) {
        return FeePolicyAssignmentResponse.builder()
                .id(entity.getId())
                .sourceKind(entity.getSourceKind())
                .subKey(entity.getSubKey())
                .policyKey(entity.getPolicyKey())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
