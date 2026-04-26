package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F02.2.1: {@link DashboardService#getTeamDashboard} の可視性フィルタを検証する単体テスト。
 *
 * <p>既存 {@code DashboardServiceTest} には触れず、F02.2.1 で導入された
 * 「viewer_role による各ウィジェットの null フィルタ」と
 * 「レスポンスの viewer_role / widget_visibility 含有」を検証する。</p>
 *
 * <ul>
 *   <li>ADMIN は全ウィジェットがレスポンスに含まれる（バイパス）</li>
 *   <li>PUBLIC は min_role=MEMBER のウィジェットが null になる</li>
 *   <li>レスポンスの viewer_role / widget_visibility が正しく組み立てられる</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardService 可視性フィルタ単体テスト (F02.2.1)")
class DashboardServiceVisibilityFilterTest {

    @Mock private DashboardWidgetService widgetService;
    @Mock private NameResolverService nameResolverService;
    @Mock private AccessControlService accessControlService;
    @Mock private ActivityFeedService activityFeedService;
    @Mock private RoleResolver roleResolver;
    @Mock private WidgetVisibilityResolver widgetVisibilityResolver;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private BulletinReadStatusRepository bulletinReadStatusRepository;
    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock private PlatformAnnouncementRepository platformAnnouncementRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;

    /**
     * チームスコープの全管理対象ウィジェット 8 件のデフォルト可視性マップを返す。
     * （実コードの WidgetDefaultMinRoleMap.getDefaultsForScope と一致）
     */
    private static Map<WidgetKey, MinRole> teamDefaultVisibilityMap() {
        Map<WidgetKey, MinRole> map = new EnumMap<>(WidgetKey.class);
        map.put(WidgetKey.TEAM_NOTICES, MinRole.PUBLIC);
        map.put(WidgetKey.TEAM_UPCOMING_EVENTS, MinRole.PUBLIC);
        map.put(WidgetKey.TEAM_TODO, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_PROJECT_PROGRESS, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_ACTIVITY, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_LATEST_POSTS, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_UNREAD_THREADS, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_MEMBER_ATTENDANCE, MinRole.MEMBER);
        return map;
    }

    @BeforeEach
    void stubCommonRepositories() {
        // チーム所属チェック通過
        // 各リポジトリは「空集合を返す」スタブで getTeamDashboard 全体を素通りさせる
        given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                .willReturn(List.of());
        given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(timelinePostRepository.findFeedByScopeType(anyString(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(List.of());
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), anyBoolean()))
                .willReturn(List.of());
    }

    // ════════════════════════════════════════════════
    // ADMIN: 全ウィジェットバイパス
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("ADMIN: 可視性フィルタをバイパス")
    class AdminBypass {

        @Test
        @DisplayName("ADMIN ロールなら min_role=MEMBER のウィジェットも全てレスポンスに含まれる")
        void ADMIN_全ウィジェット可視() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // ADMIN なら全ウィジェットが non-null
            assertThat(response.getTeamNotices()).isNotNull();
            assertThat(response.getTeamUpcomingEvents()).isNotNull();
            assertThat(response.getTeamTodo()).isNotNull();         // MEMBER 限定でも見える
            assertThat(response.getTeamActivity()).isNotNull();
            assertThat(response.getTeamLatestPosts()).isNotNull();
            assertThat(response.getTeamUnreadThreads()).isNotNull();
            assertThat(response.getTeamMemberAttendance()).isNotNull(); // MEMBER 限定でも見える

            // ADMIN なので viewerRole は ADMIN
            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.ADMIN);
        }

        @Test
        @DisplayName("ADMIN レスポンスの widget_visibility は全 is_visible=true")
        void ADMIN_widgetVisibility_全可視() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            List<WidgetVisibilityRowDto> rows = response.getWidgetVisibility();
            assertThat(rows).hasSize(8);
            assertThat(rows).allSatisfy(row -> assertThat(row.isVisible()).isTrue());
        }
    }

    // ════════════════════════════════════════════════
    // PUBLIC: MEMBER 限定ウィジェットを null にする
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PUBLIC: 内部運用情報を非表示にする")
    class PublicFiltering {

        @Test
        @DisplayName("PUBLIC viewer は min_role=MEMBER のウィジェットが null")
        void PUBLIC_MEMBER限定_null() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.PUBLIC);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // PUBLIC でも見られるもの
            assertThat(response.getTeamNotices()).isNotNull();          // PUBLIC
            assertThat(response.getTeamUpcomingEvents()).isNotNull();   // PUBLIC

            // SUPPORTER 限定 → PUBLIC からは非表示
            assertThat(response.getTeamLatestPosts()).isNull();
            assertThat(response.getTeamActivity()).isNull();

            // MEMBER 限定 → PUBLIC からは非表示
            assertThat(response.getTeamTodo()).isNull();
            assertThat(response.getTeamProjectProgress()).isNull();
            assertThat(response.getTeamUnreadThreads()).isNull();
            assertThat(response.getTeamMemberAttendance()).isNull();

            // viewer_role はレスポンスに含まれる
            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.PUBLIC);
        }

        @Test
        @DisplayName("PUBLIC レスポンスの widget_visibility は MEMBER/SUPPORTER 限定が is_visible=false")
        void PUBLIC_widgetVisibility() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.PUBLIC);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            List<WidgetVisibilityRowDto> rows = response.getWidgetVisibility();
            assertThat(rows).hasSize(8);

            // PUBLIC のウィジェットだけ可視
            WidgetVisibilityRowDto noticesRow = rows.stream()
                    .filter(r -> WidgetKey.TEAM_NOTICES.name().equals(r.getWidgetKey()))
                    .findFirst().orElseThrow();
            assertThat(noticesRow.isVisible()).isTrue();

            // MEMBER 限定は不可視
            WidgetVisibilityRowDto attendanceRow = rows.stream()
                    .filter(r -> WidgetKey.TEAM_MEMBER_ATTENDANCE.name().equals(r.getWidgetKey()))
                    .findFirst().orElseThrow();
            assertThat(attendanceRow.isVisible()).isFalse();
            assertThat(attendanceRow.getMinRole()).isEqualTo(MinRole.MEMBER);
        }
    }

    // ════════════════════════════════════════════════
    // MEMBER / SUPPORTER の階層検証
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("MEMBER / SUPPORTER: 階層に応じた可視性")
    class HierarchyFiltering {

        @Test
        @DisplayName("MEMBER → MEMBER 限定も SUPPORTER 限定も全て見える")
        void MEMBER_MEMBER限定とSUPPORTER限定_可視() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.MEMBER);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            assertThat(response.getTeamTodo()).isNotNull();              // MEMBER
            assertThat(response.getTeamMemberAttendance()).isNotNull();  // MEMBER
            assertThat(response.getTeamLatestPosts()).isNotNull();       // SUPPORTER
            assertThat(response.getTeamActivity()).isNotNull();          // SUPPORTER
            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.MEMBER);
        }

        @Test
        @DisplayName("SUPPORTER → SUPPORTER 限定は見えるが MEMBER 限定は null")
        void SUPPORTER_MEMBER限定はnull() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.SUPPORTER);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // SUPPORTER 限定は見える
            assertThat(response.getTeamLatestPosts()).isNotNull();
            assertThat(response.getTeamActivity()).isNotNull();
            // MEMBER 限定は見えない
            assertThat(response.getTeamTodo()).isNull();
            assertThat(response.getTeamMemberAttendance()).isNull();
            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.SUPPORTER);
        }
    }

    // ════════════════════════════════════════════════
    // ADMIN 限定ウィジェット
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("ADMIN 限定ウィジェット (TEAM_BILLING / TEAM_PAGE_VIEWS)")
    class AdminOnlyWidgets {

        @Test
        @DisplayName("ADMIN: team_billing が non-null（既存 isAdmin フラグの挙動）")
        void ADMIN_TEAM_BILLING() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // F02.2 既存ルールに従い、ADMIN なら teamBilling は空 Map（non-null）
            assertThat(response.getTeamBilling()).isNotNull();
        }

        @Test
        @DisplayName("MEMBER: team_billing は null（ADMIN 限定）")
        void MEMBER_TEAM_BILLING_null() {
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID))
                    .willReturn(ViewerRole.MEMBER);
            given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID))
                    .willReturn(teamDefaultVisibilityMap());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamDashboardResponse response =
                    dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            assertThat(response.getTeamBilling()).isNull();
        }
    }
}
