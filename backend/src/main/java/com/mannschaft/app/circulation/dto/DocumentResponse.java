package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回覧文書レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DocumentResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long createdBy;
    private final String title;
    private final String body;
    private final String circulationMode;
    private final Integer sequentialCount;
    private final String status;
    private final String priority;
    private final LocalDate dueDate;
    private final Boolean reminderEnabled;
    private final Short reminderIntervalHours;
    private final String stampDisplayStyle;
    private final Integer totalRecipientCount;
    private final Integer stampedCount;
    private final LocalDateTime completedAt;
    private final Integer attachmentCount;
    private final Integer commentCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
