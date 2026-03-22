package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * プロモーションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PromotionResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long createdBy;
    private final String title;
    private final String body;
    private final String imageUrl;
    private final Long couponId;
    private final String status;
    private final Long approvedBy;
    private final LocalDateTime approvedAt;
    private final LocalDateTime scheduledAt;
    private final LocalDateTime publishedAt;
    private final LocalDateTime expiresAt;
    private final Integer targetCount;
    private final Integer deliveredCount;
    private final Integer openedCount;
    private final Integer skippedCount;
    private final Integer failedCount;
    private final List<SegmentCondition> segments;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
