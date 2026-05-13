package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 相見積もりカンバン作成リクエスト（F08.8 Phase 4）。
 *
 * @param title              カンバンタイトル（必須・最大200文字）
 * @param workPackageId      紐付ける work_package_id（F09.13、クロスドメインID参照・null可）
 * @param repairPlanItemId   紐付ける修繕計画項目ID（null可）
 * @param bidDeadlineAt      入札締切日時（必須）
 * @param visibilityToMember 住民への公開レベル（HIDDEN / ANONYMIZED / FULL）
 */
public record CreateKanbanRequest(
        @NotBlank @Size(max = 200) String title,
        Long workPackageId,
        UUID repairPlanItemId,
        @NotNull Instant bidDeadlineAt,
        @NotNull String visibilityToMember
) {}
