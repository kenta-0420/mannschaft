package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * TODOレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TodoResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long projectId;
    private final Long milestoneId;
    private final String title;
    private final String description;
    private final String status;
    private final String priority;
    private final LocalDate dueDate;
    private final LocalTime dueTime;
    private final Long daysRemaining;
    private final LocalDateTime completedAt;
    private final ProjectResponse.UserInfo completedBy;
    private final ProjectResponse.UserInfo createdBy;
    private final int sortOrder;
    private final List<AssigneeResponse> assignees;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final Long parentId;
    private final Integer depth;
    private final List<TodoResponse> children;
    private final int childCount;
    private final int descendantCompletedCount;
    private final int descendantTotalCount;

    // Phase 2 追加フィールド
    /** 開始日（ガントバー表示用）。 */
    private final LocalDate startDate;

    /** 連携スケジュールID。 */
    private final Long linkedScheduleId;

    /** 進捗率（0.00〜100.00）。 */
    private final BigDecimal progressRate;

    /** 進捗率が手動設定かどうか。falseの場合は子から自動計算される。 */
    private final Boolean progressManual;
}
