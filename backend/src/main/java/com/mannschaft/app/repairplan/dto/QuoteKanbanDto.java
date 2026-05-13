package com.mannschaft.app.repairplan.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 相見積もりカンバン DTO（F08.8 Phase 4）。
 *
 * @param id                 カンバンID
 * @param title              カンバンタイトル
 * @param scopeType          スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId            スコープID
 * @param organizationId     組織ID
 * @param workPackageId      紐付け work_package_id（null可）
 * @param repairPlanItemId   紐付け修繕計画項目ID（null可）
 * @param bidDeadlineAt      入札締切日時
 * @param visibilityToMember 住民公開レベル
 * @param status             カンバンステータス（OPEN / CLOSED / AWARDED / CANCELED）
 * @param cards              カード一覧（visibility フィルタ適用済み）
 * @param createdAt          作成日時
 * @param updatedAt          更新日時
 */
public record QuoteKanbanDto(
        UUID id,
        String title,
        String scopeType,
        Long scopeId,
        Long organizationId,
        Long workPackageId,
        UUID repairPlanItemId,
        LocalDateTime bidDeadlineAt,
        String visibilityToMember,
        String status,
        List<QuoteCardDto> cards,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
