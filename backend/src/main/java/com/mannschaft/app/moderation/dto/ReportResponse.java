package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コンテンツ通報レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReportResponse {

    private final Long id;
    private final String targetType;
    private final Long targetId;
    private final String reporterType;
    private final Long reporterId;
    private final String reason;
    private final String description;
    private final String status;
    private final Long reviewedBy;
    private final String reviewNote;
    private final Boolean identityDisclosed;
    private final LocalDateTime resolvedAt;
    private final LocalDateTime createdAt;
}
