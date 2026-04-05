package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モデレーションダッシュボードレスポンスDTO。全体統計を含む。
 */
@Getter
@RequiredArgsConstructor
public class ModerationDashboardResponse {

    private final long pendingReportsCount;
    private final long pendingAppealsCount;
    private final long pendingReReviewsCount;
    private final long escalatedReReviewsCount;
    private final long pendingUnflagRequestsCount;
    private final long activeViolationsCount;
    private final long yabaiUsersCount;
}
