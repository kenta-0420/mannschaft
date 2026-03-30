package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.SystemAdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.repository.AdminMaintenanceScheduleRepository;
import com.mannschaft.app.admin.service.SystemAdminDashboardService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SystemAdminDashboardService} の単体テスト。
 * システム管理者向けダッシュボード情報取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminDashboardService 単体テスト")
class SystemAdminDashboardServiceTest {

    @Mock
    private FeedbackSubmissionRepository feedbackRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentReportRepository contentReportRepository;

    @Mock
    private AdminMaintenanceScheduleRepository maintenanceScheduleRepository;

    @InjectMocks
    private SystemAdminDashboardService service;

    // ========================================
    // getDashboard
    // ========================================

    @Nested
    @DisplayName("getDashboard")
    class GetDashboard {

        @Test
        @DisplayName("正常系: システム管理者ダッシュボード情報が返却される")
        void 取得_正常_ダッシュボード返却() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdIsNullAndStatus("PLATFORM", FeedbackStatus.OPEN))
                    .willReturn(5L);
            given(organizationRepository.count()).willReturn(10L);
            given(teamRepository.count()).willReturn(50L);
            given(userRepository.count()).willReturn(1000L);
            given(contentReportRepository.countByStatus(ReportStatus.PENDING)).willReturn(3L);
            given(maintenanceScheduleRepository.countByStatus(MaintenanceStatus.ACTIVE)).willReturn(1L);
            given(userRepository.countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(
                    any(LocalDateTime.class), eq(UserEntity.UserStatus.ACTIVE))).willReturn(1000L);

            // When
            SystemAdminDashboardResponse result = service.getDashboard();

            // Then
            assertThat(result.getTotalOrganizations()).isEqualTo(10L);
            assertThat(result.getTotalTeams()).isEqualTo(50L);
            assertThat(result.getTotalUsers()).isEqualTo(1000L);
            assertThat(result.getActiveUsers()).isEqualTo(1000L);
            assertThat(result.getPendingReports()).isEqualTo(3L);
            assertThat(result.getOpenFeedbacks()).isEqualTo(5L);
            assertThat(result.getActiveMaintenances()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常系: 全カウントがゼロの場合でも正常に返却される")
        void 取得_全カウントゼロ_正常返却() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdIsNullAndStatus("PLATFORM", FeedbackStatus.OPEN))
                    .willReturn(0L);
            given(organizationRepository.count()).willReturn(0L);
            given(teamRepository.count()).willReturn(0L);
            given(userRepository.count()).willReturn(0L);
            given(contentReportRepository.countByStatus(ReportStatus.PENDING)).willReturn(0L);
            given(maintenanceScheduleRepository.countByStatus(MaintenanceStatus.ACTIVE)).willReturn(0L);
            given(userRepository.countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(
                    any(LocalDateTime.class), eq(UserEntity.UserStatus.ACTIVE))).willReturn(0L);

            // When
            SystemAdminDashboardResponse result = service.getDashboard();

            // Then
            assertThat(result.getTotalOrganizations()).isZero();
            assertThat(result.getTotalTeams()).isZero();
            assertThat(result.getTotalUsers()).isZero();
            assertThat(result.getPendingReports()).isZero();
            assertThat(result.getOpenFeedbacks()).isZero();
            assertThat(result.getActiveMaintenances()).isZero();
            verify(organizationRepository).count();
            verify(teamRepository).count();
            verify(userRepository).count();
        }
    }
}
