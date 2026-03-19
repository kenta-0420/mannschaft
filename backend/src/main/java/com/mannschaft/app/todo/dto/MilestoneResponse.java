package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイルストーンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MilestoneResponse {

    private final Long id;
    private final Long projectId;
    private final String title;
    private final LocalDate dueDate;
    private final short sortOrder;
    private final boolean isCompleted;
    private final LocalDateTime completedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
