package com.mannschaft.app.dashboard;

import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
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
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.repository.TeamRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private TeamService teamService;
    @Mock private OrganizationService organizationService;
    @Mock private ScopeActionRequiredFacade scopeActionRequiredFacade;

    @InjectMocks
    private DashboardController dashboardController;

    private static final Long USER_ID = 1L;
    private static final UUID TEAM_UUID = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final Long TEAM_ID = 10L;
    private static final UUID ORG_UUID = UUID.fromString("00000000-0000-7000-8000-000000000014");
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
            given(teamService.resolveTeamId(TEAM_UUID)).willReturn(TEAM_ID);
            TeamDashboardResponse mockResponse = TeamDashboardResponse.builder()
                    .widgetSettings(List.of())
                    .teamNotices(List.of())
                    .build();
            given(dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK")).willReturn(mockResponse);

            // When
            ResponseEntity<ApiResponse<TeamDashboardResponse>> response =
                    dashboardController.getTeamDashboard(TEAM_UUID, "WEEK");

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
            given(organizationService.resolveOrgId(ORG_UUID)).willReturn(ORG_ID);
            OrgDashboardResponse mockResponse = OrgDashboardResponse.builder()
                    .widgetSettings(List.of())
                    .orgTeamList(List.of())
                    .build();
            given(dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK")).willReturn(mockResponse);

            // When
            ResponseEntity<ApiResponse<OrgDashboardResponse>> response =
                    dashboardController.getOrgDashboard(ORG_UUID, "WEEK");

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

        @AfterEach
        void clearTimezone() {
            TimezoneContextHolder.clear();
        }

        @Test
        @DisplayName("正常系: 直近イベントが200で返る")
        void getUpcomingEvents_正常_200() {
            // Given
            // コントローラは findByUserIdAndTeamIdIsNullAndOrganizationIdIsNull... を呼ぶため正しいメソッドをモック
            given(scheduleRepository
                    .findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(
                            eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                    dashboardController.getUpcomingEvents(7);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("回帰: 取得ウィンドウの開始がユーザーTZの当日00:00（start-of-day）であること（欠陥②の回帰防止）")
        void getUpcomingEvents_ウィンドウ開始が当日0時() {
            // Given: UTC タイムゾーンをセット（テストの安定性のため明示指定）
            TimezoneContextHolder.set(ZoneOffset.UTC);

            ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> untilCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

            given(scheduleRepository
                    .findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(
                            eq(USER_ID), fromCaptor.capture(), untilCaptor.capture()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                    dashboardController.getUpcomingEvents(7);

            // Then: ウィンドウ開始が当日00:00であること（現在時刻起点ではなく当日0時起点）
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            LocalDateTime capturedFrom = fromCaptor.getValue();
            assertThat(capturedFrom.toLocalTime())
                    .as("getUpcomingEvents の from は当日00:00（LocalTime.MIDNIGHT）であること")
                    .isEqualTo(LocalTime.MIDNIGHT);
            // untilは from + days 日後の同時刻
            assertThat(untilCaptor.getValue())
                    .as("until は from の 7 日後であること")
                    .isEqualTo(capturedFrom.plusDays(7));
        }

        @Test
        @DisplayName("認可漏れ回帰: filterAccessible が返さないチーム/組織予定は items に含まれない")
        void getUpcomingEvents_可視性フィルタで非可視のチーム組織予定を除外する() {
            // Given: 個人予定は対象外で常に含まれる
            ScheduleEntity personal = buildSchedule(1L, null, null);
            ScheduleEntity teamVisible = buildSchedule(10L, TEAM_ID, null);
            ScheduleEntity teamHidden = buildSchedule(11L, TEAM_ID, null);
            ScheduleEntity orgVisible = buildSchedule(20L, null, ORG_ID);
            ScheduleEntity orgHidden = buildSchedule(21L, null, ORG_ID);

            given(scheduleRepository
                    .findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(
                            eq(USER_ID), any(), any()))
                    .willReturn(List.of(personal));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(UserRoleEntity.builder().teamId(TEAM_ID).build()));
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID))
                    .willReturn(List.of(UserRoleEntity.builder().organizationId(ORG_ID).build()));
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(teamVisible, teamHidden));
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(ORG_ID), any(), any()))
                    .willReturn(List.of(orgVisible, orgHidden));

            // filterAccessible は teamVisible / orgVisible のみを可視とする
            given(contentVisibilityChecker.filterAccessible(
                    eq(ReferenceType.SCHEDULE), any(), eq(USER_ID)))
                    .willReturn(Set.of(10L, 20L));

            // When
            ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                    dashboardController.getUpcomingEvents(7);

            // Then: 個人(1) + 可視チーム(10) + 可視組織(20) の 3 件。非可視(11/21)は除外。
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> items = response.getBody().getData();
            assertThat(items).extracting(m -> m.get("id"))
                    .containsExactlyInAnyOrder(1L, 10L, 20L)
                    .doesNotContain(11L, 21L);
        }

        @Test
        @DisplayName("認可漏れ回帰: 個人予定は filterAccessible を通さず常に含まれる")
        void getUpcomingEvents_個人予定はフィルタ対象外で常に含まれる() {
            // Given: チーム/組織は無く、個人予定のみ
            ScheduleEntity personal = buildSchedule(1L, null, null);
            given(scheduleRepository
                    .findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(
                            eq(USER_ID), any(), any()))
                    .willReturn(List.of(personal));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                    dashboardController.getUpcomingEvents(7);

            // Then: 個人予定は含まれ、team/org が空のため filterAccessible は呼ばれない
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).extracting(m -> m.get("id")).containsExactly(1L);
            verify(contentVisibilityChecker, never())
                    .filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(USER_ID));
        }

        /**
         * テスト用スケジュールを構築する。toScheduleBaseMap が参照する最小フィールドのみ設定する。
         */
        private ScheduleEntity buildSchedule(Long id, Long teamId, Long orgId) {
            ScheduleEntity entity = ScheduleEntity.builder()
                    .teamId(teamId)
                    .organizationId(orgId)
                    .title("予定" + id)
                    .startAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                    .endAt(LocalDateTime.of(2026, 6, 1, 11, 0))
                    .allDay(false)
                    .build();
            ReflectionTestUtils.setField(entity, "id", id);
            return entity;
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
