package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * プロジェクト詳細レスポンスDTO。マイルストーン別の進捗内訳を含む。
 */
@Getter
@RequiredArgsConstructor
public class ProjectDetailResponse {

    private final Long id;
    private final String title;
    private final String description;
    private final String emoji;
    private final String color;
    private final LocalDate dueDate;
    private final Long daysRemaining;
    private final String status;
    private final BigDecimal progressRate;
    private final int totalTodos;
    private final int completedTodos;
    private final String visibility;
    private final List<MilestoneDetail> milestones;
    private final UnassignedTodos unassignedTodos;
    private final ProjectResponse.UserInfo createdBy;

    /**
     * マイルストーン詳細。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MilestoneDetail {
        private final Long id;
        private final String title;
        private final LocalDate dueDate;
        private final boolean isCompleted;
        private final LocalDateTime completedAt;
        private final BigDecimal progressRate;
        private final long totalTodos;
        private final long completedTodos;
        private final short sortOrder;
    }

    /**
     * マイルストーン未割り当てTODO集計。
     */
    @Getter
    @RequiredArgsConstructor
    public static class UnassignedTodos {
        private final long total;
        private final long completed;
    }
}
