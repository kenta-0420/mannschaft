package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** 本人の個人札一覧専用レスポンス。公開市場の summary へ公開先 ID を漏らさない。 */
@Getter
@AllArgsConstructor
public class PersonalMarketListingSummaryResponse {

    private final Long id;
    private final Long categoryId;
    private final String categoryNameI18nKey;
    private final String title;
    private final String participationType;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final LocalDateTime applicationDeadline;
    private final Integer capacity;
    private final Integer minCapacity;
    private final Integer confirmedCount;
    private final Integer waitlistCount;
    private final String status;
    private final String visibility;
    private final String location;
    private final String imageUrl;
    private final Boolean paymentEnabled;
    private final Integer price;
    private final List<PersonalMarketAudienceScopeResponse> audienceScopes;
}
