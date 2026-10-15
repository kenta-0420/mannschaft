package com.mannschaft.app.todo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * プロジェクト全体のマイルストーンゲート状態サマリーレスポンス（F02.7）。
 *
 * <p>設計書 §4 {@code GET /api/v1/teams/{teamId}/projects/{id}/gates} のレスポンス形式。
 * 全体進捗率・ゲート完了率・次のロック中マイルストーンなどを返す。</p>
 */
public record GatesSummaryResponse(
        Long projectId,
        /** 全 TODO 完了率（completed_todos / total_todos * 100） */
        BigDecimal overallProgressRate,
        /** 完了マイルストーン率（completed_milestones / total_milestones * 100） */
        BigDecimal gateCompletionRate,
        int totalMilestones,
        int completedMilestones,
        int lockedMilestones,
        /** 次のロック中マイルストーン（全アンロック済みなら null） */
        NextGate nextGate,
        List<MilestoneGateInfo> milestones
) {

    /**
     * 次の関所情報。
     */
    public record NextGate(
            Long id,
            String title,
            Long lockedReasonMilestoneId,
            String lockedReasonMilestoneTitle,
            BigDecimal previousProgressRate
    ) {}

    /**
     * マイルストーン個別のゲート情報。
     */
    public record MilestoneGateInfo(
            Long id,
            String title,
            Short sortOrder,
            boolean isCompleted,
            boolean isLocked,
            Long lockedByMilestoneId,
            String lockedByMilestoneTitle,
            BigDecimal progressRate,
            String completionMode,
            long totalTodos,
            long completedTodos,
            long lockedTodoCount,
            LocalDateTime lockedAt,
            LocalDateTime completedAt
    ) {}
}
