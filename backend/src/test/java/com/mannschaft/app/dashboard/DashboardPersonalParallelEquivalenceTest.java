package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * 個人ダッシュボード第2段階の並列化 機能的同値の試練（受け入れ条件 (B)）。
 *
 * <p>第2段階（投稿/掲示板未読/チャット未読/アクティビティ/カレンダー集計）を
 * {@code CompletableFuture} で並列取得しても、レスポンスの内容（各ウィジェットの値）が
 * 直列実行時と完全に一致することを保証する。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-B1: ALL 優先度で第2段階の全ウィジェット（myPosts / unreadThreads / recentActivity /
 *       personalCalendar）が並列化後も期待値どおり充填される。</li>
 *   <li>AC-B2: 並列化により値が欠落・破壊されない（チャット未読合計・未読掲示板数・投稿件数の同値）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ダッシュボード 個人第2段階 並列化 機能同値 (B)")
class DashboardPersonalParallelEquivalenceTest {

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
    @Mock private com.mannschaft.app.common.visibility.ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_A = 10L;
    private static final Long TEAM_B = 11L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        stubCommonPersonalForAll();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserRoleEntity teamRole(Long teamId) {
        return UserRoleEntity.builder().userId(USER_ID).teamId(teamId).build();
    }

    private TimelinePostEntity post(long id, String content) {
        TimelinePostEntity p = TimelinePostEntity.builder().content(content).build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    @DisplayName("AC-B1/B2: 全第2段階ウィジェットが並列化後も期待値どおり充填され破壊されない")
    void 並列化後も全ウィジェット同値() {
        // チーム所属（掲示板・カレンダー集計に使う）。
        given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                .willReturn(List.of(TEAM_A, TEAM_B));

        // 投稿 2 件。
        given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .willReturn(List.of(post(1L, "投稿1"), post(2L, "投稿2")));

        // 掲示板スレッド 5 件中 2 件既読 → 未読 3。
        given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection()))
                .willReturn(List.of(100L, 101L, 102L, 103L, 104L));
        given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), eq(USER_ID)))
                .willReturn(List.of(100L, 101L));

        // チャット未読合計 = 3 + 5 = 8。
        ChatChannelMemberEntity c1 = ChatChannelMemberEntity.builder()
                .userId(USER_ID).channelId(1L).unreadCount(3).build();
        ChatChannelMemberEntity c2 = ChatChannelMemberEntity.builder()
                .userId(USER_ID).channelId(2L).unreadCount(5).build();
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of(c1, c2));

        // アクティビティ 2 件。
        ActivityFeedResponse a1 = new ActivityFeedResponse(
                1L, "POST", null, "TEAM", TEAM_A, "チームA", null, null, "投稿しました", null, null);
        ActivityFeedResponse a2 = new ActivityFeedResponse(
                2L, "EVENT", null, "TEAM", TEAM_B, "チームB", null, null, "予定を追加", null, null);
        given(activityFeedService.getActivityFeed(eq(USER_ID), isNull(), anyInt(), any()))
                .willReturn(List.of(a1, a2));

        PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

        // myPosts: 2 件。
        assertThat(result.getMyPosts()).hasSize(2);
        // unreadThreads: 掲示板未読 3、チャット未読 8。
        assertThat(result.getUnreadThreads().get("total_unread_bulletin")).isEqualTo(3L);
        assertThat(result.getUnreadThreads().get("total_unread_chat")).isEqualTo(8L);
        // recentActivity: 2 件、各値が保持される。
        assertThat(result.getRecentActivity()).hasSize(2);
        assertThat(result.getRecentActivity().get(0).get("type")).isEqualTo("POST");
        // personalCalendar: 充填されている（件数は別テストで検証）。
        assertThat(result.getPersonalCalendar()).isNotNull();
        assertThat(result.getPersonalCalendar()).containsKeys(
                "events_today", "events_this_week", "events_this_month");
    }

    @Test
    @DisplayName("CRITICAL 優先度では第2段階を実行しない（並列化対象外）")
    void CRITICAL優先度_第2段階なし() {
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

        assertThat(result.getMyPosts()).isNull();
        assertThat(result.getUnreadThreads()).isNull();
        assertThat(result.getRecentActivity()).isNull();
        assertThat(result.getPersonalCalendar()).isNull();
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
