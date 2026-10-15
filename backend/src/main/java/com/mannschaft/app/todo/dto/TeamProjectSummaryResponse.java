package com.mannschaft.app.todo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイページ チームプロジェクト集約レスポンス DTO。
 *
 * <p>{@code GET /api/v1/me/team-projects} 用。ログインユーザーが所属する全チームのプロジェクトを
 * 1 リクエストで返すため、{@link ProjectResponse} の各項目に所属チームの識別情報
 * （teamId / teamName / teamSlug）を付与する。</p>
 */
public record TeamProjectSummaryResponse(
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
        Long teamId,
        String teamName,
        String teamSlug) {
}
