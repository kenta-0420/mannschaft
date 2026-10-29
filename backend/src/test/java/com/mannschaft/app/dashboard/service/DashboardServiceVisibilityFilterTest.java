package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    @Mock private MembershipScopeQueryService membershipScopeQueryService;
    @Mock private AnnouncementFeedQueryRepository announcementFeedQueryRepository;
    @Mock private com.mannschaft.app.social.announcement.audience.AnnouncementAudienceMatcher announcementAudienceMatcher;
    @Mock private com.mannschaft.app.team.service.TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    @Mock private PaymentGateService paymentGateService;
    @Mock private com.mannschaft.app.social.announcement.AnnouncementReadService announcementReadService;
    @Mock private ScopeWidgetSummaryService scopeWidgetSummaryService;
    @Mock private ScopeActionRequiredFacade scopeActionRequiredFacade;
    @Mock private SwipeWidgetVisibilityResolver swipeWidgetVisibilityResolver;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;

    private AnnouncementFeedEntity announcement(long id) {
        AnnouncementFeedEntity feed = mock(AnnouncementFeedEntity.class);
        given(feed.getId()).willReturn(id);
        given(feed.getTitleCache()).willReturn("title-" + id);
        given(feed.getExcerptCache()).willReturn("secret-excerpt");
        given(feed.getSourceType()).willReturn(AnnouncementSourceType.BLOG_POST);
        given(feed.getSourceId()).willReturn(900L + id);
        given(feed.getTargetTeamIds()).willReturn("[100]");
        given(feed.getScopeType()).willReturn(AnnouncementScopeType.TEAM);
        given(feed.getScopeId()).willReturn(TEAM_ID);
        return feed;
    }

    /**
     * チームスコープの全管理対象ウィジェット 10 件のデフォルト可視性マップを返す。
     * （実コードの WidgetDefaultMinRoleMap.getDefaultsForScope と一致。
     *  F08.7.1 で TEAM_TOURNAMENT_RECORD / TEAM_DIVISION_STANDINGS の 2 件追加）
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
        // F08.7.1: 大会成績ウィジェット
        map.put(WidgetKey.TEAM_TOURNAMENT_RECORD, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_DIVISION_STANDINGS, MinRole.SUPPORTER);
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
        given(timelinePostRepository.findFeedByScopeType(any(com.mannschaft.app.timeline.PostScopeType.class), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(List.of());
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), anyBoolean()))
                .willReturn(List.of());

        // F22.1 第二波: SWIPE 可視性は素通し（filterIfVisible は arg3 をそのまま返す）。
        given(swipeWidgetVisibilityResolver.resolve(any(), any())).willReturn(java.util.Map.of());
        given(swipeWidgetVisibilityResolver.filterIfVisible(any(), any(), any(), any()))
                .willAnswer(inv -> inv.getArgument(3));
        given(announcementFeedQueryRepository.findByScope(
                any(com.mannschaft.app.social.announcement.AnnouncementScopeType.class),
                anyLong(), any(), any(), anyInt()))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("TEAM告知はHIDDEN除外、LOCKEDは最小情報、batch判定を適用する")
    void teamAnnouncementsApplyPaymentGateState() {
        given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
        given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID)).willReturn(teamDefaultVisibilityMap());
        given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
        AnnouncementFeedEntity hidden = announcement(3L);
        AnnouncementFeedEntity locked = announcement(2L);
        AnnouncementFeedEntity full = announcement(1L);
        given(announcementFeedQueryRepository.findByScope(
                eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), eq(10)))
                .willReturn(List.of(hidden, locked, full));
        given(paymentGateService.checkAccessBatch(
                eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                .willReturn(Map.of(
                        3L, new GateCheckResponse(false, true, List.of()),
                        2L, new GateCheckResponse(false, false, List.of()),
                        1L, new GateCheckResponse(true, false, List.of())));

        TeamDashboardResponse response = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notices = (List<Map<String, Object>>) response.getTeamNotices();
        assertThat(notices).hasSize(2);
        Map<String, Object> lockedMap = notices.stream()
                .filter(item -> item.get("id").equals(2L)).findFirst().orElseThrow();
        assertThat(lockedMap).containsEntry("title_cache", "title-2")
                .containsEntry("access_state", "LOCKED")
                .doesNotContainKeys("excerpt_cache", "source_type", "target_team_ids");
        assertThat(notices.stream().map(item -> item.get("id"))).containsExactly(2L, 1L);
        verify(paymentGateService).checkAccessBatch(
                eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class));
    }

    // ════════════════════════════════════════════════
    // CMP-261011-0035 ALIC-1（試練 red）
    // ════════════════════════════════════════════════

    private static final Long ORG_ID = 700L;

    private AnnouncementFeedEntity orgAnnouncement(long id, String visibility) {
        AnnouncementFeedEntity feed = announcement(id);
        given(feed.getScopeType()).willReturn(AnnouncementScopeType.ORGANIZATION);
        given(feed.getScopeId()).willReturn(ORG_ID);
        given(feed.getVisibility()).willReturn(visibility);
        given(feed.getTargetTeamIds()).willReturn(null);
        return feed;
    }

    /** キャッシュなしの役割解決（2入口とも）が返す最新の役割を固定する。指定のないスコープは PUBLIC。 */
    private void freshRoles(ViewerRole teamRole, ViewerRole orgRole) {
        given(roleResolver.resolveViewerRoleForPreview(eq(USER_ID), eq("TEAM"), eq(TEAM_ID))).willReturn(teamRole);
        given(roleResolver.resolveViewerRoleForPreview(eq(USER_ID), eq("ORGANIZATION"), eq(ORG_ID))).willReturn(orgRole);
        given(roleResolver.resolveViewerRolesFresh(eq(USER_ID), any())).willAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Set<com.mannschaft.app.common.visibility.ScopeKey> scopes =
                    (java.util.Set<com.mannschaft.app.common.visibility.ScopeKey>) inv.getArgument(1);
            Map<com.mannschaft.app.common.visibility.ScopeKey, ViewerRole> result = new java.util.LinkedHashMap<>();
            for (var scope : scopes) {
                ViewerRole role = "TEAM".equals(scope.scopeType()) && TEAM_ID.equals(scope.scopeId()) ? teamRole
                        : "ORGANIZATION".equals(scope.scopeType()) && ORG_ID.equals(scope.scopeId()) ? orgRole
                        : ViewerRole.PUBLIC;
                result.put(scope, role);
            }
            return result;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> notices(TeamDashboardResponse response) {
        return (List<Map<String, Object>>) response.getTeamNotices();
    }

    @Test
    @DisplayName("AC-ALIC1-M2 チーム ADMIN でも組織告知の課金 LOCKED は素通しせず LOCKED のまま（チーム告知の ADMIN 素通しは従来どおり）")
    void チームADMINは組織告知の課金を素通ししない() {
        given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.ADMIN);
        freshRoles(ViewerRole.ADMIN, ViewerRole.PUBLIC);
        given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID)).willReturn(teamDefaultVisibilityMap());
        given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        AnnouncementFeedEntity teamFeed = announcement(1L);
        AnnouncementFeedEntity orgFeed = orgAnnouncement(2L, "PUBLIC");
        given(announcementFeedQueryRepository.findByScope(
                eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), eq(10)))
                .willReturn(List.of(teamFeed));
        given(announcementAudienceMatcher.findVisibleOrgFeeds(eq(TEAM_ID), any(), anyInt()))
                .willReturn(List.of(orgFeed));
        // 両方とも未購入（accessible=false・タイトルは見せる）＝ LOCKED 相当
        given(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                .willAnswer(inv -> {
                    Map<Long, GateCheckResponse> gates = new java.util.HashMap<>();
                    for (Object id : (java.util.Collection<?>) inv.getArgument(1)) {
                        gates.put((Long) id, new GateCheckResponse(false, false, List.of()));
                    }
                    return gates;
                });

        List<Map<String, Object>> notices = notices(dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK"));

        Map<String, Object> org = notices.stream().filter(item -> item.get("id").equals(2L)).findFirst()
                .orElseThrow(() -> new AssertionError("前提: 組織告知がダッシュボードに出ている"));
        assertThat(org).as("チーム ADMIN は組織の課金を素通ししない（組織ロールは PUBLIC）")
                .containsEntry("access_state", "LOCKED")
                .doesNotContainKeys("excerpt_cache", "source_type", "source_id", "target_team_ids");
        Map<String, Object> team = notices.stream().filter(item -> item.get("id").equals(1L)).findFirst().orElseThrow();
        assertThat(team).as("チーム告知はチーム ADMIN の素通しを維持（回帰）").containsEntry("access_state", "FULL");
    }

    @Test
    @DisplayName("AC-ALIC1-E01(単体) 告知部分はキャッシュなしの役割で visibility を絞る（キャッシュ MEMBER・最新 SUPPORTER なら MEMBERS を出さない）")
    void 告知の可視性はキャッシュなしの役割で判定する() {
        given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
        freshRoles(ViewerRole.SUPPORTER, ViewerRole.PUBLIC);
        given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID)).willReturn(teamDefaultVisibilityMap());
        given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
        AnnouncementFeedEntity membersFeed = announcement(5L);
        given(membersFeed.getVisibility()).willReturn("MEMBERS_AND_ABOVE");
        AnnouncementFeedEntity supportersFeed = announcement(6L);
        given(supportersFeed.getVisibility()).willReturn("SUPPORTERS_AND_ABOVE");
        // 問い合わせに渡された visibility 集合に従って返す（DB と同じ振る舞い）
        given(announcementFeedQueryRepository.findByScope(
                eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), anyInt()))
                .willAnswer(inv -> {
                    java.util.Collection<?> allowed = inv.getArgument(2);
                    return java.util.stream.Stream.of(membersFeed, supportersFeed)
                            .filter(feed -> allowed == null || allowed.contains(feed.getVisibility())).toList();
                });
        given(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                .willReturn(Map.of(5L, new GateCheckResponse(true, false, List.of()),
                        6L, new GateCheckResponse(true, false, List.of())));

        List<Map<String, Object>> notices = notices(dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK"));

        assertThat(notices.stream().map(item -> item.get("id")))
                .as("キャッシュの MEMBER ではなく最新の SUPPORTER で絞る").containsExactly(6L);
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
            assertThat(rows).hasSize(10);
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
            assertThat(rows).hasSize(10);

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
