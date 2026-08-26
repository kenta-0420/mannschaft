package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.dashboard.controller.DashboardController;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.ChatHubService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * スケジュール集計の N+1 根治（IN 句バッチ化）の試練。
 *
 * <p>受け入れ条件:
 * <ul>
 *   <li>AC-14: N チーム所属でも {@code findByTeamIdAndStartAtBetweenOrderByStartAtAsc}
 *       が呼ばれず、バッチ取得メソッド {@code findByTeamIdInAndStartAtBetween} が定数回で済む。</li>
 *   <li>AC-15: 複数チーム×複数スケジュールで eventsToday/Week/Month の集計値が従来と一致する。</li>
 *   <li>AC-16: teamRoles が空のときバッチメソッドを呼ばない（IN () を発行しない）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ダッシュボード スケジュール集計 N+1 バッチ化")
class DashboardScheduleBatchN1Test {

    @Mock private DashboardWidgetService widgetService;
    @Mock private NameResolverService nameResolverService;
    @Mock private AccessControlService accessControlService;
    @Mock private ActivityFeedService activityFeedService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private BulletinReadStatusRepository bulletinReadStatusRepository;
    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock private PlatformAnnouncementRepository platformAnnouncementRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private AnnouncementFeedQueryRepository announcementFeedQueryRepository;
    @Mock private com.mannschaft.app.dashboard.service.RoleResolver roleResolver;
    @Mock private com.mannschaft.app.dashboard.service.WidgetVisibilityResolver widgetVisibilityResolver;
    @Mock private com.mannschaft.app.dashboard.service.ScopeWidgetSummaryService scopeWidgetSummaryService;
    @Mock private com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade scopeActionRequiredFacade;
    @Mock private com.mannschaft.app.dashboard.service.SwipeWidgetVisibilityResolver swipeWidgetVisibilityResolver;

    @InjectMocks
    private DashboardService dashboardService;

    // ============ Controller 用の追加モック ============
    @Mock private ChatHubService chatHubService;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private OrganizationService organizationService;
    @Mock private TeamService teamService;
    /** Controller の DashboardService 依存はモックに差し替える（getCalendar は service を呼ばないため挙動に影響なし）。 */
    @Mock private DashboardService dashboardServiceMock;
    @Mock private com.mannschaft.app.admin.service.PlatformAnnouncementService platformAnnouncementService;

    private DashboardController dashboardController;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_A = 10L;
    private static final Long TEAM_B = 11L;
    private static final Long TEAM_C = 12L;

    @BeforeEach
    void setUp() {
        // Resolver デフォルトスタブ（ADMIN バイパス）。
        org.mockito.Mockito.lenient().when(roleResolver.resolveViewerRole(anyLong(), any(), anyLong()))
                .thenReturn(com.mannschaft.app.dashboard.ViewerRole.ADMIN);
        org.mockito.Mockito.lenient().when(widgetVisibilityResolver.resolve(any(), anyLong()))
                .thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(swipeWidgetVisibilityResolver.resolve(any(), anyLong()))
                .thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(swipeWidgetVisibilityResolver.filterIfVisible(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
        // CMP-017b 第五隊: filterAccessible は既定で「渡された ID を全て可視」として通す
        // （本テストの主眼は N+1 バッチ化であり可視性判定そのものは対象外のため pass-through）。
        org.mockito.Mockito.lenient()
                .when(contentVisibilityChecker.filterAccessible(any(), anyCollection(), any()))
                .thenAnswer(inv -> {
                    java.util.Collection<Long> ids = inv.getArgument(1);
                    return new java.util.HashSet<>(ids);
                });

        dashboardController = new DashboardController(
                dashboardServiceMock,
                widgetService,
                activityFeedService,
                chatHubService,
                accessControlService,
                notificationRepository,
                timelinePostRepository,
                scheduleRepository,
                userRoleRepository,
                bulletinThreadRepository,
                bulletinReadStatusRepository,
                chatChannelMemberRepository,
                teamRepository,
                organizationRepository,
                contentVisibilityChecker,
                shiftAssignmentRepository,
                reservationRepository,
                scopeActionRequiredFacade,
                organizationService,
                teamService,
                platformAnnouncementService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserRoleEntity teamRole(Long teamId) {
        return UserRoleEntity.builder().userId(USER_ID).teamId(teamId).build();
    }

    private ScheduleEntity teamSchedule(Long teamId, LocalDateTime startAt, long id) {
        ScheduleEntity s = ScheduleEntity.builder()
                .teamId(teamId)
                .title("予定" + id)
                .startAt(startAt)
                .eventType(EventType.MATCH)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    // =====================================================
    // AC-14: 旧メソッドを呼ばず、新バッチメソッドが定数回
    // =====================================================

    @Nested
    @DisplayName("AC-14 N+1 解消（呼び出し回数の定数化）")
    class Ac14BatchQuery {

        @Test
        @DisplayName("Controller.getCalendar: 3チーム所属でも旧teamメソッドは呼ばれず新バッチメソッドが定数回"
                + "（CMP-017b 第五隊: 期間別3回発行を最広範囲1回に統合＋可視性判定を追加）")
        void getCalendar_3チーム_旧メソッド未呼出_新バッチ1回() {
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A, TEAM_B, TEAM_C));
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(anyCollection(), any(), any()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<Map<String, Object>>> response = dashboardController.getCalendar(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // 旧 N+1 メソッドは一切呼ばれない。
            verify(scheduleRepository, never())
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any());
            // 最広範囲（todayStart〜monthEnd）を 1 回だけ取得し、today/week/month はアプリ層で集計する
            // （AC-24: 可視性判定を追加しても SQL 本数は増やさない）。
            verify(scheduleRepository, times(1))
                    .findByTeamIdInAndStartAtBetween(anyCollection(), any(), any());
        }

        @Test
        @DisplayName("Service.getPersonalDashboard: 3チーム所属でも旧teamメソッドは呼ばれず新バッチメソッドのみ使用")
        void personalDashboard_3チーム_旧メソッド未呼出_新バッチ使用() {
            stubCommonPersonalForAll();
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A, TEAM_B, TEAM_C));
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(anyCollection(), any(), any()))
                    .willReturn(List.of());

            dashboardService.getPersonalDashboard(USER_ID, "ALL");

            verify(scheduleRepository, never())
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any());
        }
    }

    // =====================================================
    // AC-15: 集計値が従来と一致（回帰なし）
    // =====================================================

    @Nested
    @DisplayName("AC-15 集計値の従来一致（回帰防止）")
    class Ac15Aggregation {

        @Test
        @DisplayName("Controller.getCalendar: 複数チーム×複数予定で today/week/month が期待値どおり集計される"
                + "（CMP-017b 第五隊: 最広範囲1回取得＋アプリ層集計に統合後も従来と同じ集計結果）")
        void getCalendar_複数チーム複数予定_集計値一致() {
            LocalDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
            // 個人予定は 0 件。
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A, TEAM_B));

            // 最広範囲（todayStart〜monthEnd）を 1 回だけ取得する。5件のうち
            // 1,2 が today 以内、1,2,3,4 が week 以内、全件が month 以内。
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(todayStart.plusMonths(1))))
                    .willReturn(List.of(
                            teamSchedule(TEAM_A, todayStart.plusHours(1), 1L),
                            teamSchedule(TEAM_B, todayStart.plusHours(2), 2L),
                            teamSchedule(TEAM_A, todayStart.plusDays(3), 3L),
                            teamSchedule(TEAM_B, todayStart.plusDays(5), 4L),
                            teamSchedule(TEAM_A, todayStart.plusDays(20), 5L)));

            ResponseEntity<ApiResponse<Map<String, Object>>> response = dashboardController.getCalendar(null);
            Map<String, Object> data = response.getBody().getData();

            assertThat(data.get("events_today")).isEqualTo(2L);
            assertThat(data.get("events_this_week")).isEqualTo(4L);
            assertThat(data.get("events_this_month")).isEqualTo(5L);
        }

        @Test
        @DisplayName("Controller.getCalendar: min_view_role で不可視な予定は集計から除外される"
                + "（CMP-017b 第五隊 AC-13: 応援者に MEMBER_PLUS 予定の件数を漏らさない）")
        void getCalendar_不可視予定は集計から除外() {
            LocalDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A));

            ScheduleEntity visible = teamSchedule(TEAM_A, todayStart.plusHours(1), 1L);
            ScheduleEntity hidden = teamSchedule(TEAM_A, todayStart.plusHours(2), 2L);
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(todayStart.plusMonths(1))))
                    .willReturn(List.of(visible, hidden));
            // filterAccessible は id=1 のみ可視として返す（id=2 は SUPPORTER に不可視な MEMBER_PLUS 予定）。
            given(contentVisibilityChecker.filterAccessible(any(), anyCollection(), any()))
                    .willReturn(Set.of(1L));

            ResponseEntity<ApiResponse<Map<String, Object>>> response = dashboardController.getCalendar(null);
            Map<String, Object> data = response.getBody().getData();

            assertThat(data.get("events_today")).isEqualTo(1L);
            assertThat(data.get("events_this_week")).isEqualTo(1L);
            assertThat(data.get("events_this_month")).isEqualTo(1L);
        }
    }

    // =====================================================
    // AC-16: teamRoles 空のとき IN () を発行しない
    // =====================================================

    @Nested
    @DisplayName("AC-16 空集合ガード（IN () 非発行）")
    class Ac16EmptyGuard {

        @Test
        @DisplayName("Controller.getCalendar: teamRoles が空ならバッチメソッドを呼ばない")
        void getCalendar_teamRoles空_バッチ未呼出() {
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

            dashboardController.getCalendar(null);

            verify(scheduleRepository, never())
                    .findByTeamIdInAndStartAtBetween(anyCollection(), any(), any());
            verify(scheduleRepository, never())
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Service.getPersonalDashboard: teamRoles が空ならバッチメソッドを呼ばない")
        void personalDashboard_teamRoles空_バッチ未呼出() {
            stubCommonPersonalForAll();
            given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

            dashboardService.getPersonalDashboard(USER_ID, "ALL");

            verify(scheduleRepository, never())
                    .findByTeamIdInAndStartAtBetween(anyCollection(), any(), any());
            verify(scheduleRepository, never())
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any());
        }
    }

    // ============ ヘルパー ============

    private void stubCommonPersonalForAll() {
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                .willReturn(List.of());
        given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
        given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);
        given(notificationRepository.countByUserId(USER_ID)).willReturn(0L);
        given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
        given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .willReturn(List.of());
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), anyLong(), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        given(activityFeedService.getActivityFeed(eq(USER_ID), any(), any(Integer.class), any(), any()))
                .willReturn(new com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse(List.of(), null));
    }
}
