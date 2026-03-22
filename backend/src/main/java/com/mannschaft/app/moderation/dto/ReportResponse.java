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
    private final Long reportedBy;
    private final String scopeType;
    private final Long scopeId;
    private final Long targetUserId;
    private final String reason;
    private final String description;
    private final String contentSnapshot;
    private final String status;
    private final Long reviewedBy;
    private final LocalDateTime reviewedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
