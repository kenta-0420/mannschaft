package com.mannschaft.app.dashboard;

import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.controller.DashboardController;
import com.mannschaft.app.dashboard.dto.ChatHubResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.ChatHubService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardController} の単体テスト。
 * セキュリティコンテキストを直接設定してコントローラーをインスタンス化してテストする。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardController 単体テスト")
class DashboardControllerTest {

    @Mock private DashboardService dashboardService;
    @Mock private DashboardWidgetService widgetService;
    @Mock private ActivityFeedService activityFeedService;
    @Mock private ChatHubService chatHubService;
    @Mock private AccessControlService accessControlService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private BulletinReadStatusRepository bulletinReadStatusRepository;
    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;

    @InjectMocks
    private DashboardController dashboardController;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // getPersonalDashboard
    // ========================================

    @Nested
    @DisplayName("getPersonalDashboard")
    class GetPersonalDashboard {

        @Test
        @DisplayName("正常系: 個人ダッシュボードが200で返る")
        void getPersonalDashboard_正常_200() {
            // Given
            PersonalDashboardResponse mockResponse = PersonalDashboardResponse.builder()
                    .greeting(null)
                    .widgetSettings(List.of())
                    .build();
            given(dashboardService.getPersonalDashboard(USER_ID, "ALL")).willReturn(mockResponse);

            // When
            ResponseEntity<ApiResponse<PersonalDashboardResponse>> response =
                    dashboardController.getPersonalDashboard("ALL");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isNotNull();
            verify(dashboardService).getPersonalDashboard(USER_ID, "ALL");
        }
    }

    // ========================================
    // getTeamDashboard
    // ========================================

    @Nested
    @DisplayName("getTeamDashboard")
    class GetTeamDashboard {

        @Test
        @DisplayName("正常系: チームダッシュボードが200で返る")
        void getTeamDashboard_正常_200() {
            // Given
            TeamDashboardResponse mockResponse = TeamDashboardResponse.builder()
                    .widgetSettings(List.of())
                    .teamNotices(List.of())
                    .build();
            given(dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK")).willReturn(mockResponse);

            // When
            ResponseEntity<ApiResponse<TeamDashboardResponse>> response =
                    dashboardController.getTeamDashboard(TEAM_ID, "WEEK");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
            verify(dashboardService).getTeamDashboard(USER_ID, TEAM_ID, "WEEK");
        }
    }

    // ========================================
    // getOrgDashboard
    // ========================================

    @Nested
    @DisplayName("getOrgDashboard")
    class GetOrgDashboard {

        @Test
        @DisplayName("正常系: 組織ダッシュボードが200で返る")
        void getOrgDashboard_正常_200() {
            // Given
            OrgDashboardResponse mockResponse = OrgDashboardResponse.builder()
                    .widgetSettings(List.of())
                    .orgTeamList(List.of())
                    .build();
            given(dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK")).willReturn(mockResponse);

            // When
            ResponseEntity<ApiResponse<OrgDashboardResponse>> response =
                    dashboardController.getOrgDashboard(ORG_ID, "WEEK");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
            verify(dashboardService).getOrgDashboard(USER_ID, ORG_ID, "WEEK");
        }
    }

    // ========================================
    // getPerformance
    // ========================================

    @Nested
    @DisplayName("getPerformance")
    class GetPerformance {

        @Test
        @DisplayName("正常系: パフォーマンスサマリーが200で返る")
        void getPerformance_正常_200() {
            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response = dashboardController.getPerformance();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).containsKey("teams");
        }
    }

    // ========================================
    // getChatHub
    // ========================================

    @Nested
    @DisplayName("getChatHub")
    class GetChatHub {

        @Test
        @DisplayName("正常系: チャットハブが200で返る")
        void getChatHub_正常_200() {
            // Given
            ChatHubResponse hubResponse = new ChatHubResponse(List.of(), List.of(), List.of(), null);
            given(chatHubService.getChatHub(USER_ID)).willReturn(hubResponse);

            // When
            ResponseEntity<ApiResponse<ChatHubResponse>> response = dashboardController.getChatHub();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }
    }

    // ========================================
    // getWidgetSettings
    // ========================================

    @Nested
    @DisplayName("getWidgetSettings")
    class GetWidgetSettings {

        @Test
        @DisplayName("正常系: ウィジェット設定一覧が200で返る")
        void getWidgetSettings_正常_200() {
            // Given
            given(widgetService.parseScopeType("PERSONAL")).willReturn(ScopeType.PERSONAL);
            given(widgetService.resolveScopeId(ScopeType.PERSONAL, null)).willReturn(0L);
            given(accessControlService.isAdminOrAbove(USER_ID, 0L, "PERSONAL")).willReturn(false);
            given(widgetService.getWidgetSettings(USER_ID, ScopeType.PERSONAL, 0L, false))
                    .willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> response =
                    dashboardController.getWidgetSettings("PERSONAL", null);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }
    }

    // ========================================
    // updateWidgetSettings
    // ========================================

    @Nested
    @DisplayName("updateWidgetSettings")
    class UpdateWidgetSettings {

        @Test
        @DisplayName("正常系: ウィジェット設定が更新されて200が返る")
        void updateWidgetSettings_正常_200() {
            // Given
            UpdateWidgetSettingsRequest request = new UpdateWidgetSettingsRequest("PERSONAL", 0L, List.of());
            given(widgetService.updateWidgetSettings(USER_ID, request)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> response =
                    dashboardController.updateWidgetSettings(request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(widgetService).updateWidgetSettings(USER_ID, request);
        }
    }

    // ========================================
    // resetWidgetSettings
    // ========================================

    @Nested
    @DisplayName("resetWidgetSettings")
    class ResetWidgetSettings {

        @Test
        @DisplayName("正常系: ウィジェット設定がリセットされて204が返る")
        void resetWidgetSettings_正常_204() {
            // Given
            given(widgetService.parseScopeType("TEAM")).willReturn(ScopeType.TEAM);
            given(widgetService.resolveScopeId(ScopeType.TEAM, TEAM_ID)).willReturn(TEAM_ID);

            // When
            ResponseEntity<Void> response = dashboardController.resetWidgetSettings("TEAM", TEAM_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(widgetService).resetWidgetSettings(USER_ID, ScopeType.TEAM, TEAM_ID);
        }
    }

    // ========================================
    // getNotices
    // ========================================

    @Nested
    @DisplayName("getNotices")
    class GetNotices {

        @Test
        @DisplayName("正常系: isRead=falseで未読通知のみ取得")
        void getNotices_isReadFalse_未読のみ() {
            // Given
            given(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(
                    eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                    dashboardController.getNotices(null, 20, false);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).containsKey("items");
            assertThat(response.getBody().getData()).containsKey("meta");
        }

        @Test
        @DisplayName("正常系: isRead=nullで全通知取得")
        void getNotices_isReadNull_全通知() {
            // Given
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(
                    eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                    dashboardController.getNotices(null, 20, null);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: limit > 50の場合は50に切り詰まる")
        void getNotices_limit超過_50に切り詰め() {
            // Given
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(
                    eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                    dashboardController.getNotices(null, 100, null);

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) response.getBody().getData().get("meta");
            assertThat((Integer) meta.get("limit")).isEqualTo(50);
        }
    }

    // ========================================
    // getUpcomingEvents
    // ========================================

    @Nested
    @DisplayName("getUpcomingEvents")
    class GetUpcomingEvents {

        @Test
        @DisplayName("正常系: 直近イベントが200で返る")
        void getUpcomingEvents_正常_200() {
            // Given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                    dashboardController.getUpcomingEvents(7);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }
    }

    // ========================================
    // getUnreadThreads
    // ========================================

    @Nested
    @DisplayName("getUnreadThreads")
    class GetUnreadThreads {

        @Test
        @DisplayName("正常系: 未読スレッドが200で返る")
        void getUnreadThreads_正常_200() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                    dashboardController.getUnreadThreads(10);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).containsKey("bulletin_threads");
            assertThat(response.getBody().getData()).containsKey("total_unread_bulletin");
        }
    }

    // ========================================
    // getActivity
    // ========================================

    @Nested
    @DisplayName("getActivity")
    class GetActivity {

        @Test
        @DisplayName("正常系: アクティビティが200で返る")
        void getActivity_正常_200() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(activityFeedService.getActivityFeed(eq(USER_ID), any(), any(Integer.class), any()))
                    .willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<com.mannschaft.app.dashboard.dto.ActivityFeedResponse>>> response =
                    dashboardController.getActivity(null, 10);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }
    }

    // ========================================
    // getCalendar
    // ========================================

    @Nested
    @DisplayName("getCalendar")
    class GetCalendar {

        @Test
        @DisplayName("正常系: カレンダーサマリーが200で返る")
        void getCalendar_正常_200() {
            // Given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response = dashboardController.getCalendar(null);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).containsKey("events_today");
            assertThat(response.getBody().getData()).containsKey("events_this_week");
        }
    }

    // ========================================
    // getMyPosts
    // ========================================

    @Nested
    @DisplayName("getMyPosts")
    class GetMyPosts {

        @Test
        @DisplayName("正常系: 自分の投稿一覧が200で返る")
        void getMyPosts_正常_200() {
            // Given
            given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> response =
                    dashboardController.getMyPosts(null, 10);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).containsKey("items");
            assertThat(response.getBody().getData()).containsKey("meta");
        }
    }
}
