package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
import static org.mockito.Mockito.verify;

/**
 * 個人ダッシュボード 掲示板未読数 N+1 根治の試練（受け入れ条件 (A)）。
 *
 * <p>従来は所属チーム数 N に対し
 * {@code findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc}（チームごと 1）と、
 * 各スレッドごとの {@code existsByThreadIdAndUserId}（スレッド数 M ごと 1）を直列発行しており、
 * 最悪 N(M+1) クエリになっていた。これを <b>2 クエリ</b>（スレッド ID 一括取得 + 既読 ID 一括取得）に圧縮する。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-A1: 掲示板未読集計でスレッドごとの {@code existsByThreadIdAndUserId} を 1 度も呼ばない（N+1 撲滅）。</li>
 *   <li>AC-A2: スレッド ID 一括取得は <b>1 クエリ</b>、既読 ID 一括取得は <b>高々 1 クエリ</b>（O(1)〜O(2)）。</li>
 *   <li>AC-A3: 複数チーム×複数スレッド×一部既読のシードで {@code total_unread_bulletin} がリファクタ前後で一致する。</li>
 *   <li>AC-A4: 所属チームが無い場合は既読バッチクエリを発行しない（IN () 非発行）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ダッシュボード 掲示板未読数 N+1 バッチ化 (A)")
class DashboardBulletinUnreadN1Test {

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

    // =====================================================
    // AC-A1 / AC-A2: N+1 撲滅・クエリ数 O(1)〜O(2)
    // =====================================================

    @Nested
    @DisplayName("AC-A1/A2 N+1 撲滅・クエリ定数化")
    class BatchQuery {

        @Test
        @DisplayName("2チーム×複数スレッドでも existsByThreadIdAndUserId は呼ばれず、ID一括取得1回＋既読一括取得≦1回")
        void 掲示板未読_existsを呼ばず一括取得() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(teamRole(TEAM_A), teamRole(TEAM_B)));
            // チーム横断のスレッド ID を 1 クエリで取得（TEAM スコープ × teamIds IN）。
            given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                    eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection()))
                    .willReturn(List.of(100L, 101L, 102L, 103L));
            // 既読スレッド ID を 1 クエリで取得。
            given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), eq(USER_ID)))
                    .willReturn(List.of(100L, 102L));

            dashboardService.getPersonalDashboard(USER_ID, "ALL");

            // スレッドごとの存在判定（N+1 の元凶）は 1 度も呼ばれない。
            verify(bulletinReadStatusRepository, never()).existsByThreadIdAndUserId(anyLong(), anyLong());
            // 旧 N+1 のチームごとスレッド取得も呼ばれない。
            verify(bulletinThreadRepository, never())
                    .findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), anyLong(), any());
            // スレッド ID 一括取得は 1 クエリ、既読 ID 一括取得は高々 1 クエリ。
            verify(bulletinThreadRepository)
                    .findIdsByScopeTypeAndScopeIdIn(eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection());
            verify(bulletinReadStatusRepository, atMost(1)).findReadThreadIds(anyCollection(), eq(USER_ID));
        }
    }

    // =====================================================
    // AC-A3: 未読カウントの正しさ（回帰防止）
    // =====================================================

    @Nested
    @DisplayName("AC-A3 未読カウントの正しさ")
    class UnreadCorrectness {

        @Test
        @DisplayName("スレッド4件中2件既読 → total_unread_bulletin=2")
        void 未読カウント_一部既読() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(teamRole(TEAM_A), teamRole(TEAM_B)));
            given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                    eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection()))
                    .willReturn(List.of(100L, 101L, 102L, 103L));
            given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), eq(USER_ID)))
                    .willReturn(List.of(100L, 102L));

            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            assertThat(result.getUnreadThreads().get("total_unread_bulletin")).isEqualTo(2L);
        }

        @Test
        @DisplayName("全件未読 → total_unread_bulletin=スレッド数")
        void 未読カウント_全件未読() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(teamRole(TEAM_A)));
            given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                    eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection()))
                    .willReturn(List.of(200L, 201L, 202L));
            given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), eq(USER_ID)))
                    .willReturn(List.of());

            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            assertThat(result.getUnreadThreads().get("total_unread_bulletin")).isEqualTo(3L);
        }

        @Test
        @DisplayName("全件既読 → total_unread_bulletin=0")
        void 未読カウント_全件既読() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(teamRole(TEAM_A)));
            given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                    eq(com.mannschaft.app.bulletin.ScopeType.TEAM), anyCollection()))
                    .willReturn(List.of(300L, 301L));
            given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), eq(USER_ID)))
                    .willReturn(List.of(300L, 301L));

            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            assertThat(result.getUnreadThreads().get("total_unread_bulletin")).isEqualTo(0L);
        }
    }

    // =====================================================
    // AC-A4: 所属チーム無しは既読バッチを呼ばない
    // =====================================================

    @Nested
    @DisplayName("AC-A4 空集合ガード")
    class EmptyGuard {

        @Test
        @DisplayName("所属チーム無し → スレッドID取得は空、既読バッチは未呼出、未読0")
        void 所属チーム無し_バッチ未呼出() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());

            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            assertThat(result.getUnreadThreads().get("total_unread_bulletin")).isEqualTo(0L);
            verify(bulletinReadStatusRepository, never()).findReadThreadIds(anyCollection(), anyLong());
            verify(bulletinReadStatusRepository, never()).existsByThreadIdAndUserId(anyLong(), anyLong());
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
        given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(scheduleRepository.findByTeamIdInAndStartAtBetween(anyCollection(), any(), any()))
                .willReturn(List.of());
        given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());
        given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any()))
                .willReturn(List.of());
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        // 旧 N+1 経路（チームごとのスレッド取得）— 移行後は呼ばれないが、念のため空ページを返す。
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), anyLong(), any()))
                .willReturn(new PageImpl<>(List.of()));
        // 新バッチ経路のデフォルト（個別テストで上書き）。
        given(bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(any(), anyCollection()))
                .willReturn(List.of());
        given(bulletinReadStatusRepository.findReadThreadIds(anyCollection(), anyLong()))
                .willReturn(List.of());
        given(activityFeedService.getActivityFeed(eq(USER_ID), isNull(), anyInt(), any()))
                .willReturn(List.of());
    }
}
