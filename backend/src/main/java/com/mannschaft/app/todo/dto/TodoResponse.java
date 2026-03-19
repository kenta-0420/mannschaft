package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
}
