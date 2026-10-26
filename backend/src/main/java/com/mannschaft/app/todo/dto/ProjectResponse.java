package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * プロジェクト一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProjectResponse {

    private final Long id;
    private final String title;
    private final String emoji;
    private final String color;
    private final LocalDate dueDate;
    private final Long daysRemaining;
    private final String status;
    private final BigDecimal progressRate;
    private final int totalTodos;
    private final int completedTodos;
    private final MilestoneSummary milestones;
    private final UserInfo createdBy;
    private final LocalDateTime createdAt;

    /**
     * マイルストーンサマリー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MilestoneSummary {
        private final long total;
        private final long completed;
    }

    /**
     * ユーザー情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class UserInfo {
        private final Long id;
        private final String displayName;
    }
}
