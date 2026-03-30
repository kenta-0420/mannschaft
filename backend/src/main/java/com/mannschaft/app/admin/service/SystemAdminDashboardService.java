package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.MaintenanceStatus;
import com.mannschaft.app.admin.dto.SystemAdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.repository.AdminMaintenanceScheduleRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * システム管理者ダッシュボードサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAdminDashboardService {

    private final FeedbackSubmissionRepository feedbackRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final ContentReportRepository contentReportRepository;
    private final AdminMaintenanceScheduleRepository maintenanceScheduleRepository;

    /**
     * システム管理者ダッシュボード情報を取得する。
     *
     * @return ダッシュボード情報
     */
    public SystemAdminDashboardResponse getDashboard() {
        long openFeedbacks = feedbackRepository.countByScopeTypeAndScopeIdIsNullAndStatus(
                "PLATFORM", FeedbackStatus.OPEN);

        long totalOrganizations = organizationRepository.count();
        long totalTeams = teamRepository.count();
        long totalUsers = userRepository.count();
        long pendingReports = contentReportRepository.countByStatus(ReportStatus.PENDING);
        long activeMaintenances = maintenanceScheduleRepository.countByStatus(MaintenanceStatus.ACTIVE);

        return new SystemAdminDashboardResponse(
                totalOrganizations,
                totalTeams,
                totalUsers,
                userRepository.countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(
                        LocalDateTime.now().minusDays(30), UserEntity.UserStatus.ACTIVE),
                pendingReports,
                openFeedbacks,
                activeMaintenances
        );
    }
}
