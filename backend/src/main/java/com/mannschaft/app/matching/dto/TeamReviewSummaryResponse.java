package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * チームレビューサマリーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TeamReviewSummaryResponse {

    private final Long teamId;
    private final Double averageRating;
    private final Long reviewCount;
    private final List<ReviewResponse> reviews;
}
