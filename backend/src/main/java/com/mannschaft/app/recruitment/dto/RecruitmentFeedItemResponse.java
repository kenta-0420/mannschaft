package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: マイフィード（フォロー先・サポーター先の新着募集）の1件レスポンス。
 * Phase 2 で追加。
 */
@Getter
@AllArgsConstructor
public class RecruitmentFeedItemResponse {

    private final Long id;
    private final Long categoryId;
    private final String categoryNameI18nKey;
    private final Long scopeId;
    private final String scopeType;
    private final String title;
    private final String description;
    private final String participationType;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final LocalDateTime applicationDeadline;
    private final Integer capacity;
    private final Integer confirmedCount;
    private final String status;
    private final String visibility;
    private final String location;
    private final String imageUrl;
    private final Boolean paymentEnabled;
    private final Integer price;
    private final LocalDateTime createdAt;
}
