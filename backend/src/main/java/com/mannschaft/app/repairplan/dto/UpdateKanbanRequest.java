package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 相見積もりカンバン更新リクエスト（F08.8 Phase 4）。
 * 各フィールドは null 可（PATCH セマンティクス: non-null フィールドのみ更新）。
 *
 * @param title              カンバンタイトル（最大200文字）
 * @param bidDeadlineAt      入札締切日時
 * @param visibilityToMember 住民への公開レベル（HIDDEN / ANONYMIZED / FULL）
 * @param status             カンバンステータス（OPEN / CLOSED / AWARDED / CANCELED）
 */
public record UpdateKanbanRequest(
        @Size(max = 200) String title,
        Instant bidDeadlineAt,
        String visibilityToMember,
        String status
) {}
