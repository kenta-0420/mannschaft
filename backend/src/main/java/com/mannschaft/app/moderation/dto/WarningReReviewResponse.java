package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * WARNING再レビューレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WarningReReviewResponse {

    private final Long id;
    private final Long userId;
    private final Long reportId;
    private final Long actionId;
    private final String reason;
    private final String status;
    private final Long adminReviewedBy;
    private final String adminReviewNote;
    private final LocalDateTime adminReviewedAt;
    private final String escalationReason;
    private final Long systemAdminReviewedBy;
    private final String systemAdminReviewNote;
    private final LocalDateTime systemAdminReviewedAt;
    private final LocalDateTime createdAt;
}
