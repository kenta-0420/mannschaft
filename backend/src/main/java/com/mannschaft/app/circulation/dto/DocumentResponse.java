package com.mannschaft.app.circulation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回覧文書レスポンスDTO。
 */
@Getter
@Builder(toBuilder = true)
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

    /**
     * Phase 11: 作成者の表示名。
     * {@code CirculationService} が {@code UserRepository#findMemberSummaryById} で解決して充填する。
     * 解決できない場合は {@code null}。
     */
    private final String createdByName;
}
