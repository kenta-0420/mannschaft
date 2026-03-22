package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.dto.AdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理者ダッシュボードサービス（チーム/組織管理者向け）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final FeedbackSubmissionRepository feedbackRepository;

    /**
     * チーム/組織の管理者ダッシュボード情報を取得する。
     *
     * @param scopeType スコープ種別（ORGANIZATION/TEAM）
     * @param scopeId   スコープID
     * @return ダッシュボード情報
     */
    public AdminDashboardResponse getDashboard(String scopeType, Long scopeId) {
        long pendingFeedbacks = feedbackRepository.countByScopeTypeAndScopeIdAndStatus(
                scopeType, scopeId, FeedbackStatus.OPEN);

        // TODO: メンバー数・アクティブメンバー数・通報数・スケジュール数は各機能のリポジトリ実装後に連携
        return new AdminDashboardResponse(
                0L,  // totalMembers
                0L,  // activeMembers
                pendingFeedbacks,
                0L,  // openReports
                0L   // upcomingSchedules
        );
    }
}
