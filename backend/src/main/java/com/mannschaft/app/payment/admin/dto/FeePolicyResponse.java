package com.mannschaft.app.payment.admin.dto;

import com.mannschaft.app.payment.FeePolicyEntity;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン（{@code fee_policies}）のシスアド管理レスポンス（設計書 02 §11）。
 *
 * <p>casing は camelCase で 1:1（#1210/#1221 前科回避）。金額は {@code long}（最小通貨単位・円）。
 * {@code assignmentCount} は当該パターンを参照する未削除割当の件数（一覧の参考表示）。</p>
 *
 * @param policyKey       自然キー（{@code DEFAULT} / {@code RECRUITMENT_HELPER} 等）
 * @param displayName     管理画面表示名
 * @param percentRate     総手数料の率（{@code 0 ≤ percentRate < 1}・例 0.0500＝5%）
 * @param flatFeeMinor    総手数料の固定額（円・最小単位）
 * @param enabled         有効フラグ
 * @param description     補足説明（運用メモ・null 可）
 * @param assignmentCount 当該パターンを参照する未削除割当の件数
 * @param createdAt       作成日時
 * @param updatedAt       更新日時
 */
@Builder
public record FeePolicyResponse(
        String policyKey,
        String displayName,
        BigDecimal percentRate,
        long flatFeeMinor,
        boolean enabled,
        String description,
        long assignmentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * エンティティと割当件数からレスポンスを生成する。
     *
     * @param entity          手数料パターンエンティティ
     * @param assignmentCount 当該パターンを参照する未削除割当の件数
     * @return レスポンス
     */
    public static FeePolicyResponse from(FeePolicyEntity entity, long assignmentCount) {
        return FeePolicyResponse.builder()
                .policyKey(entity.getPolicyKey())
                .displayName(entity.getDisplayName())
                .percentRate(entity.getPercentRate())
                .flatFeeMinor(entity.getFlatFeeMinor() != null ? entity.getFlatFeeMinor() : 0L)
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .description(entity.getDescription())
                .assignmentCount(assignmentCount)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
