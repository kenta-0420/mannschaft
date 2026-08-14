package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 個人ダッシュボード スケジュール集計の重複クエリ根治の試練（受け入れ条件 (C)）。
 *
 * <p>従来は events_today / events_this_week / events_this_month を算出するため、
 * 個人スケジュールを 3 回・チームスケジュールを 3 回（計 6 クエリ）取得していた。
 * これを「最大範囲（月末まで）を 1 回取得し、アプリ層で today/week/month を集計」する形に
 * 圧縮する（個人 1 クエリ + チーム 1 バッチクエリ = 2 クエリ）。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-C1: 第2段階のカレンダー件数集計で、個人スケジュール取得（{@code findByUserIdAndStartAtBetween...}）は
 *       第2段階で <b>追加 1 回</b>のみ、チームバッチ取得（{@code findByTeamIdInAndStartAtBetween}）も <b>追加 1 回</b>のみ。
 *       期間ごと（today/week/month）に分けて 3 回ずつ呼ばない。</li>
 *   <li>AC-C2: 複数チーム・個人を跨いだシードで events_today / events_this_week / events_this_month が
 *       従来ロジックと一致する（境界含む）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ダッシュボード スケジュール集計 重複クエリ根治 (C)")
class DashboardScheduleAggregationTest {

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
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_A = 10L;
    private static final Long TEAM_B = 11L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        // CMP-017b 第五隊: filterAccessible は既定で「渡された ID を全て可視」として通す
        // （本テストの主眼は重複クエリ根治であり可視性判定そのものは対象外のため pass-through）。
        org.mockito.Mockito.lenient()
                .when(contentVisibilityChecker.filterAccessible(any(), anyCollection(), any()))
                .thenAnswer(inv -> {
                    java.util.Collection<Long> ids = inv.getArgument(1);
                    return new java.util.HashSet<>(ids);
                });
        stubCommonPersonalForAll();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserRoleEntity teamRole(Long teamId) {
        return UserRoleEntity.builder().userId(USER_ID).teamId(teamId).build();
    }

    private ScheduleEntity schedule(Long teamId, Long userId, LocalDateTime startAt, long id) {
        ScheduleEntity s = ScheduleEntity.builder()
                .teamId(teamId)
                .userId(userId)
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
    // AC-C1: 期間別の重複クエリを廃し、最大範囲1回取得に統合
    // =====================================================

    @Nested
    @DisplayName("AC-C1 重複クエリ廃止（最大範囲1回取得）")
    class SingleRangeFetch {

        @Test
        @DisplayName("第2段階の件数集計で個人取得・チームバッチ取得はそれぞれ追加1回のみ（3回ずつ呼ばない）")
        void 件数集計_最大範囲1回() {
            LocalDateTime todayStart = LocalDate.now(com.mannschaft.app.common.timezone.TimezoneContextHolder.get())
                    .atStartOfDay();
            LocalDateTime monthEnd = todayStart.plusMonths(1);

            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A, TEAM_B));
            // 最大範囲（todayStart〜monthEnd）の取得をスタブ。
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), eq(todayStart), eq(monthEnd))).willReturn(List.of());
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(monthEnd))).willReturn(List.of());

            dashboardService.getPersonalDashboard(USER_ID, "ALL");

            // 個人スケジュール取得: 第1段階の今後7日間 1 回 + 第2段階の最大範囲 1 回 = 計 2 回。
            // 重要なのは「期間別に 3 回」呼ばないこと。第2段階での最大範囲(monthEnd)取得は 1 回のみ。
            verify(scheduleRepository, times(1)).findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), eq(todayStart), eq(monthEnd));
            // チームバッチ取得は最大範囲(monthEnd) 1 回のみ（第1段階の7日間取得とは別、期間別3回でない）。
            verify(scheduleRepository, times(1)).findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(monthEnd));
            // today/week 範囲での個別取得は第2段階では発行しない（アプリ層で集計）。
            LocalDateTime todayEnd = LocalDate.now(com.mannschaft.app.common.timezone.TimezoneContextHolder.get())
                    .atTime(java.time.LocalTime.MAX);
            verify(scheduleRepository, never()).findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(todayEnd));
            verify(scheduleRepository, never()).findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(todayStart.plusDays(7)));
        }
    }

    // =====================================================
    // AC-C2: 集計値が従来ロジックと一致
    // =====================================================

    @Nested
    @DisplayName("AC-C2 集計値の従来一致")
    class AggregationCorrectness {

        @Test
        @DisplayName("個人＋2チームの予定を月内に散らし、today/week/month を境界含めて正しく集計")
        void 集計値_境界含め一致() {
            LocalDateTime todayStart = LocalDate.now(com.mannschaft.app.common.timezone.TimezoneContextHolder.get())
                    .atStartOfDay();
            LocalDateTime todayEnd = LocalDate.now(com.mannschaft.app.common.timezone.TimezoneContextHolder.get())
                    .atTime(java.time.LocalTime.MAX);
            LocalDateTime weekEnd = todayStart.plusDays(7);
            LocalDateTime monthEnd = todayStart.plusMonths(1);

            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_A, TEAM_B));

            // 個人スケジュール（最大範囲 monthEnd で 1 回取得される想定）:
            //  - today 内: 1件
            //  - week 内（today超〜+7d）: 1件
            //  - month 内（week超〜+1M）: 1件
            List<ScheduleEntity> personal = List.of(
                    schedule(null, USER_ID, todayStart.plusHours(2), 1L),     // today
                    schedule(null, USER_ID, todayStart.plusDays(3), 2L),      // week
                    schedule(null, USER_ID, todayStart.plusDays(20), 3L));    // month
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), eq(todayStart), eq(monthEnd))).willReturn(personal);

            // チームスケジュール（最大範囲 monthEnd で 1 バッチ取得される想定）:
            //  - today 内: 1件（境界 todayEnd ちょうど）
            //  - week 内: 2件
            //  - month 内: 1件
            List<ScheduleEntity> team = List.of(
                    schedule(TEAM_A, null, todayEnd, 4L),                     // today（境界ちょうど）
                    schedule(TEAM_A, null, todayStart.plusDays(2), 5L),       // week
                    schedule(TEAM_B, null, weekEnd, 6L),                      // week（境界ちょうど = +7d）
                    schedule(TEAM_B, null, todayStart.plusDays(25), 7L));     // month
            given(scheduleRepository.findByTeamIdInAndStartAtBetween(
                    anyCollection(), eq(todayStart), eq(monthEnd))).willReturn(team);

            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");
            Map<String, Object> cal = result.getPersonalCalendar();

            // today: personal 1 + team 1 = 2
            assertThat(cal.get("events_today")).isEqualTo(2L);
            // week（todayStart〜+7d, 境界含む）: personal(today1 + week1)=2 + team(today1 + week2)=3 = 5
            assertThat(cal.get("events_this_week")).isEqualTo(5L);
            // month（todayStart〜+1M）: personal 3 + team 4 = 7
            assertThat(cal.get("events_this_month")).isEqualTo(7L);
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
                .willReturn(new PageImpl<>(List.of()));
        // 第1段階の今後7日間取得（startAt は now〜now+7d。範囲一致しないスタブは any で受ける）。
        given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(scheduleRepository.findByTeamIdInAndStartAtBetween(anyCollection(), any(), any()))
                .willReturn(List.of());
        given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
        given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .willReturn(List.of());
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), anyLong(), any()))
                .willReturn(new PageImpl<>(List.of()));
        given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(any(), anyCollection()))
                .willReturn(List.of());
        given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), anyLong()))
                .willReturn(List.of());
        given(activityFeedService.getActivityFeed(eq(USER_ID), isNull(), anyInt(), any()))
                .willReturn(List.of());
    }
}
