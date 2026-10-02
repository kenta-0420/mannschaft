package com.mannschaft.app.todo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイページ 組織プロジェクト集約レスポンス DTO。
 *
 * <p>{@code GET /api/v1/me/org-projects} 用。ログインユーザーが所属する全組織のプロジェクトを
 * 1 リクエストで返すため、{@link ProjectResponse} の各項目に所属組織の識別情報
 * （orgId / orgName / orgSlug）を付与する。</p>
 *
 * <p>{@link TeamProjectSummaryResponse} の組織版。teamId/teamName/teamSlug を
 * orgId/orgName/orgSlug に置き換えた対称設計。</p>
 */
public record OrgProjectSummaryResponse(
        Long id,
        String title,
        String emoji,
        String color,
        LocalDate dueDate,
        Long daysRemaining,
        String status,
        BigDecimal progressRate,
        int totalTodos,
        int completedTodos,
        ProjectResponse.MilestoneSummary milestones,
        ProjectResponse.UserInfo createdBy,
        LocalDateTime createdAt,
        Long orgId,
        String orgName,
        String orgSlug) {
}
