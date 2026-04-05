package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * システム管理者ダッシュボードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SystemAdminDashboardResponse {

    private final Long totalOrganizations;
    private final Long totalTeams;
    private final Long totalUsers;
    private final Long activeUsers;
    private final Long pendingReports;
    private final Long openFeedbacks;
    private final Long activeMaintenances;
}
