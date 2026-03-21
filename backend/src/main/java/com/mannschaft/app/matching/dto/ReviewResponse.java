package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * レビューレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewResponse {

    private final Long id;
    private final Long proposalId;
    private final Long reviewerTeamId;
    private final Short rating;
    private final String comment;
    private final Boolean isPublic;
    private final LocalDateTime createdAt;
}
