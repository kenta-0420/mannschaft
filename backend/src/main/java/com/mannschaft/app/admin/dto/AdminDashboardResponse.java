package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 管理者ダッシュボードレスポンスDTO（チーム/組織管理者向け）。
 */
@Getter
@RequiredArgsConstructor
public class AdminDashboardResponse {

    private final Long totalMembers;
    private final Long activeMembers;
    private final Long pendingFeedbacks;
    private final Long openReports;
    private final Long upcomingSchedules;
}
