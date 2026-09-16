package com.mannschaft.app.recruitment.dto;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** 本人の個人札一覧専用レスポンス。公開市場の summary へ公開先 ID を漏らさない。 */
@Getter
public class PersonalMarketListingSummaryResponse extends RecruitmentListingSummaryResponse {

    private final List<PersonalMarketAudienceScopeResponse> audienceScopes;

    public PersonalMarketListingSummaryResponse(
            Long id,
            Long categoryId,
            String categoryNameI18nKey,
            String title,
            String participationType,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime applicationDeadline,
            Integer capacity,
            Integer minCapacity,
            Integer confirmedCount,
            Integer waitlistCount,
            String status,
            String visibility,
            String location,
            String imageUrl,
            Boolean paymentEnabled,
            Integer price,
            List<PersonalMarketAudienceScopeResponse> audienceScopes) {
        super(id, categoryId, categoryNameI18nKey, title, participationType, startAt, endAt,
                applicationDeadline, capacity, minCapacity, confirmedCount, waitlistCount,
                status, visibility, location, imageUrl, paymentEnabled, price);
        this.audienceScopes = audienceScopes;
    }
}
