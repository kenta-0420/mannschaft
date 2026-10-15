package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フィードバックレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FeedbackResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String category;
    private final String title;
    private final String body;
    private final Boolean isAnonymous;
    private final Long submittedBy;
    private final String status;
    private final String adminResponse;
    private final Long respondedBy;
    private final LocalDateTime respondedAt;
    private final Boolean isPublicResponse;
    private final Long voteCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
