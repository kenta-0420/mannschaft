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
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
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
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F02.8 ハードニング: {@link DashboardService#getTeamDashboard} の
 * 「親組織の告知フィードを feedId 単位で重複排除する」防御を検証する単体テスト。
 *
 * <p>現状は {@code user_roles} の {@code UNIQUE(user_id, scope_key)} により
 * 1 ユーザーが同一組織に複数 org ロール行を持つことは無いが、将来この制約が
 * 緩められた場合でもダッシュボードの組織告知が二重表示しないよう、
 * サービス層で {@code AnnouncementFeedEntity::getId} により明示的に dedup する。</p>
 *
 * <ul>
 *   <li>AC-1（核心の回帰）: 同一 organizationId の org ロール行が 2 件返り、
 *       同一 feedId の告知が 2 回集約されても、team_notices には 1 件だけ含まれる。</li>
 *   <li>AC-2（非回帰）: org ロール 1 件・feed 1 件なら従来通り 1 件表示される。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardService 組織告知 feedId 重複排除単体テスト (F02.8 ハードニング)")
class DashboardServiceOrgAnnouncementDedupTest {

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
    @Mock private AnnouncementFeedQueryRepository announcementFeedQueryRepository;
    @Mock private ScopeWidgetSummaryService scopeWidgetSummaryService;
    @Mock private ScopeActionRequiredFacade scopeActionRequiredFacade;
    @Mock private SwipeWidgetVisibilityResolver swipeWidgetVisibilityResolver;
    @Mock private PaymentGateService paymentGateService;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long FEED_ID = 555L;

    /**
     * チームスコープの全管理対象ウィジェットのデフォルト可視性マップ（TEAM_NOTICES=PUBLIC）。
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
        map.put(WidgetKey.TEAM_TOURNAMENT_RECORD, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_DIVISION_STANDINGS, MinRole.SUPPORTER);
        return map;
    }

    /** 組織スコープの告知フィード（チーム TEAM_ID 宛）を組み立てる。 */
    private static AnnouncementFeedEntity orgFeed(Long id) {
        return AnnouncementFeedEntity.builder()
                .id(id)
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(1L)
                .titleCache("組織A告知")
                .targetTeamIds("[" + TEAM_ID + "]")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @BeforeEach
    void stubCommonRepositories() {
        given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                .willReturn(List.of());
        given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(timelinePostRepository.findFeedByScopeType(
                any(com.mannschaft.app.timeline.PostScopeType.class), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(List.of());
        given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                any(), eq(TEAM_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
        given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), anyBoolean()))
                .willReturn(List.of());

        given(swipeWidgetVisibilityResolver.resolve(any(), any())).willReturn(java.util.Map.of());
        given(swipeWidgetVisibilityResolver.filterIfVisible(any(), any(), any(), any()))
                .willAnswer(inv -> inv.getArgument(3));

        // 告知はチームスコープ側 0 件・組織側で検証する
        given(announcementFeedQueryRepository.findByScope(
                eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(List.of());

        // ADMIN で TEAM_NOTICES を素通りさせる（filterIfVisible がリストをそのまま返す）
        given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.ADMIN);
        given(widgetVisibilityResolver.resolve("TEAM", TEAM_ID)).willReturn(teamDefaultVisibilityMap());
        given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(paymentGateService.checkAccessBatch(
                eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                .willReturn(Map.of(FEED_ID, new GateCheckResponse(true, false, List.of())));
    }

    @Test
    @DisplayName("AC-1: 同一組織の org ロール行が 2 件でも、同一 feedId の組織告知は 1 件だけ表示される")
    void AC1_多重orgロール_同一feedIdは1件に重複排除() {
        // 同一 organizationId を 2 件返す（flatMap で同一 feedId が 2 回集約される状況を模擬）。
        // 本番の findOrganizationIdsByUserId は DISTINCT だが、ここでは feedId 重複排除ロジックの
        // 検証のため意図的に重複 orgId を与える。
        given(userRoleRepository.findOrganizationIdsByUserId(USER_ID))
                .willReturn(List.of(ORG_ID, ORG_ID));
        // 各 org スコープにつき同一 feedId(=FEED_ID) の告知が返る → flatMap で 2 回集約される
        given(announcementFeedQueryRepository.findByOrgScopeForTeamDashboard(
                eq(ORG_ID), any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(List.of(orgFeed(FEED_ID)));

        TeamDashboardResponse response =
                dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notices = (List<Map<String, Object>>) (List<?>) response.getTeamNotices();
        assertThat(notices).isNotNull();
        // feedId=FEED_ID の告知はちょうど 1 件（重複排除前は 2 件になり red）
        long feedCount = notices.stream()
                .filter(m -> FEED_ID.equals(m.get("id")))
                .count();
        assertThat(feedCount)
                .as("同一 feedId の組織告知は重複排除され 1 件であること")
                .isEqualTo(1L);
        assertThat(notices).hasSize(1);
    }

    @Test
    @DisplayName("AC-2: org ロール 1 件・feed 1 件なら従来通り 1 件表示される（非回帰）")
    void AC2_単一orgロール単一feed_1件表示() {
        given(userRoleRepository.findOrganizationIdsByUserId(USER_ID))
                .willReturn(List.of(ORG_ID));
        given(announcementFeedQueryRepository.findByOrgScopeForTeamDashboard(
                eq(ORG_ID), any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(List.of(orgFeed(FEED_ID)));

        TeamDashboardResponse response =
                dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notices = (List<Map<String, Object>>) (List<?>) response.getTeamNotices();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("id")).isEqualTo(FEED_ID);
    }
}
