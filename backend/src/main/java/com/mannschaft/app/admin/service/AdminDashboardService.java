package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.dto.AdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 管理者ダッシュボードサービス（チーム/組織管理者向け）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final FeedbackSubmissionRepository feedbackRepository;
    private final UserRoleRepository userRoleRepository;
    private final ContentReportRepository contentReportRepository;
    private final ScheduleRepository scheduleRepository;

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

        long totalMembers = countMembers(scopeType, scopeId);
        long openReports = contentReportRepository.countByScopeTypeAndScopeIdAndStatus(
                scopeType, scopeId, ReportStatus.PENDING);
        long upcomingSchedules = countUpcomingSchedules(scopeType, scopeId);

        long activeMembers = countActiveMembers(scopeType, scopeId);

        return new AdminDashboardResponse(
                totalMembers,
                activeMembers,
                pendingFeedbacks,
                openReports,
                upcomingSchedules
        );
    }

    /**
     * スコープ別のメンバー数を取得する。
     */
    private long countMembers(String scopeType, Long scopeId) {
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> userRoleRepository.countByTeamId(scopeId);
            case "ORGANIZATION" -> userRoleRepository.countByOrganizationId(scopeId);
            default -> 0L;
        };
    }

    /**
     * スコープ別の今後のスケジュール数を取得する。
     */
    /**
     * スコープ別の直近30日以内にログインしたアクティブメンバー数を取得する。
     */
    private long countActiveMembers(String scopeType, Long scopeId) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        return userRoleRepository.countActiveMembers(scopeType.toUpperCase(), scopeId, since);
    }

    private long countUpcomingSchedules(String scopeType, Long scopeId) {
        LocalDateTime now = LocalDateTime.now();
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> scheduleRepository.countByTeamIdAndStartAtAfter(scopeId, now);
            case "ORGANIZATION" -> scheduleRepository.countByOrganizationIdAndStartAtAfter(scopeId, now);
            default -> 0L;
        };
    }
}
