package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.dto.SystemAdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * システム管理者ダッシュボードサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAdminDashboardService {

    private final FeedbackSubmissionRepository feedbackRepository;

    /**
     * システム管理者ダッシュボード情報を取得する。
     *
     * @return ダッシュボード情報
     */
    public SystemAdminDashboardResponse getDashboard() {
        long openFeedbacks = feedbackRepository.countByScopeTypeAndScopeIdIsNullAndStatus(
                "PLATFORM", FeedbackStatus.OPEN);

        // TODO: 組織数・チーム数・ユーザー数・通報数・メンテナンス数は各機能のリポジトリ実装後に連携
        return new SystemAdminDashboardResponse(
                0L,  // totalOrganizations
                0L,  // totalTeams
                0L,  // totalUsers
                0L,  // activeUsers
                0L,  // pendingReports
                openFeedbacks,
                0L   // activeMaintenances
        );
    }
}
