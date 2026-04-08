package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 募集枠の詳細レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentListingResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long categoryId;
    private final String categoryNameI18nKey;
    private final Long subcategoryId;
    private final String subcategoryName;
    private final String title;
    private final String description;
    private final String participationType;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final LocalDateTime applicationDeadline;
    private final LocalDateTime autoCancelAt;
    private final Integer capacity;
    private final Integer minCapacity;
    private final Integer confirmedCount;
    private final Integer waitlistCount;
    private final Integer waitlistMax;
    private final Boolean paymentEnabled;
    private final Integer price;
    private final String visibility;
    private final String status;
    private final String location;
    private final Long reservationLineId;
    private final String imageUrl;
    private final Long cancellationPolicyId;
    private final Long createdBy;
    private final LocalDateTime cancelledAt;
    private final Long cancelledBy;
    private final String cancelledReason;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
