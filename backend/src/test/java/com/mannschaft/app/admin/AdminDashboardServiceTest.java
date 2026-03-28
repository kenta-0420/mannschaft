package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.AdminDashboardResponse;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.service.AdminDashboardService;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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
 * {@link AdminDashboardService} の単体テスト。
 * チーム/組織管理者向けダッシュボード情報取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDashboardService 単体テスト")
class AdminDashboardServiceTest {

    @Mock
    private FeedbackSubmissionRepository feedbackRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ContentReportRepository contentReportRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @InjectMocks
    private AdminDashboardService service;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long SCOPE_ID = 10L;

    // ========================================
    // getDashboard
    // ========================================

    @Nested
    @DisplayName("getDashboard")
    class GetDashboard {

        @Test
        @DisplayName("正常系: TEAMスコープでダッシュボード情報が返却される")
        void 取得_TEAMスコープ_ダッシュボード返却() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdAndStatus("TEAM", SCOPE_ID, FeedbackStatus.OPEN))
                    .willReturn(3L);
            given(userRoleRepository.countByTeamId(SCOPE_ID)).willReturn(25L);
            given(contentReportRepository.countByScopeTypeAndScopeIdAndStatus("TEAM", SCOPE_ID, ReportStatus.PENDING))
                    .willReturn(2L);
            given(scheduleRepository.countByTeamIdAndStartAtAfter(eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(5L);
            given(userRoleRepository.countActiveMembers(eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(25);

            // When
            AdminDashboardResponse result = service.getDashboard("TEAM", SCOPE_ID);

            // Then
            assertThat(result.getTotalMembers()).isEqualTo(25L);
            assertThat(result.getActiveMembers()).isEqualTo(25L);
            assertThat(result.getPendingFeedbacks()).isEqualTo(3L);
            assertThat(result.getOpenReports()).isEqualTo(2L);
            assertThat(result.getUpcomingSchedules()).isEqualTo(5L);
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでダッシュボード情報が返却される")
        void 取得_ORGANIZATIONスコープ_ダッシュボード返却() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdAndStatus("ORGANIZATION", SCOPE_ID, FeedbackStatus.OPEN))
                    .willReturn(1L);
            given(userRoleRepository.countByOrganizationId(SCOPE_ID)).willReturn(100L);
            given(contentReportRepository.countByScopeTypeAndScopeIdAndStatus("ORGANIZATION", SCOPE_ID, ReportStatus.PENDING))
                    .willReturn(0L);
            given(scheduleRepository.countByOrganizationIdAndStartAtAfter(eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(10L);
            given(userRoleRepository.countActiveMembers(eq("ORGANIZATION"), eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(100);

            // When
            AdminDashboardResponse result = service.getDashboard("ORGANIZATION", SCOPE_ID);

            // Then
            assertThat(result.getTotalMembers()).isEqualTo(100L);
            assertThat(result.getActiveMembers()).isEqualTo(100L);
            assertThat(result.getPendingFeedbacks()).isEqualTo(1L);
            assertThat(result.getOpenReports()).isZero();
            assertThat(result.getUpcomingSchedules()).isEqualTo(10L);
        }

        @Test
        @DisplayName("正常系: 不明スコープの場合メンバー数とスケジュール数が0になる")
        void 取得_不明スコープ_ゼロ返却() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdAndStatus("UNKNOWN", SCOPE_ID, FeedbackStatus.OPEN))
                    .willReturn(0L);
            given(contentReportRepository.countByScopeTypeAndScopeIdAndStatus("UNKNOWN", SCOPE_ID, ReportStatus.PENDING))
                    .willReturn(0L);
            given(userRoleRepository.countActiveMembers(eq("UNKNOWN"), eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(0);

            // When
            AdminDashboardResponse result = service.getDashboard("UNKNOWN", SCOPE_ID);

            // Then
            assertThat(result.getTotalMembers()).isZero();
            assertThat(result.getUpcomingSchedules()).isZero();
        }

        @Test
        @DisplayName("正常系: 小文字スコープがtoUpperCaseで正しく処理される")
        void 取得_小文字スコープ_正常処理() {
            // Given
            given(feedbackRepository.countByScopeTypeAndScopeIdAndStatus("team", SCOPE_ID, FeedbackStatus.OPEN))
                    .willReturn(0L);
            given(userRoleRepository.countByTeamId(SCOPE_ID)).willReturn(10L);
            given(contentReportRepository.countByScopeTypeAndScopeIdAndStatus("team", SCOPE_ID, ReportStatus.PENDING))
                    .willReturn(0L);
            given(scheduleRepository.countByTeamIdAndStartAtAfter(eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(0L);
            given(userRoleRepository.countActiveMembers(eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class)))
                    .willReturn(10);

            // When
            AdminDashboardResponse result = service.getDashboard("team", SCOPE_ID);

            // Then
            assertThat(result.getTotalMembers()).isEqualTo(10L);
            verify(userRoleRepository).countByTeamId(SCOPE_ID);
        }
    }
}
