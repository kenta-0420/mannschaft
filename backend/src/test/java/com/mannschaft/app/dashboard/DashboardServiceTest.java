package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardService} の単体テスト。
 * 個人・チーム・組織ダッシュボードの一括取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 単体テスト")
class DashboardServiceTest {

    @Mock
    private DashboardWidgetService widgetService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ActivityFeedService activityFeedService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;

    @Mock
    private BulletinReadStatusRepository bulletinReadStatusRepository;

    @Mock
    private ChatChannelMemberRepository chatChannelMemberRepository;

    @Mock
    private PlatformAnnouncementRepository platformAnnouncementRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private DashboardService dashboardService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    // ========================================
    // 共通スタブヘルパー
    // ========================================

    private void stubPersonalDashboardCommon() {
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                .willReturn(List.of());
        given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
        given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);
        given(notificationRepository.countByUserId(USER_ID)).willReturn(0L);
        given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
        given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
    }

    private void stubScopeCoverage() {
        given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
        given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());
    }

    // ========================================
    // getPersonalDashboard
    // ========================================

    @Nested
    @DisplayName("getPersonalDashboard")
    class GetPersonalDashboard {

        @Test
        @DisplayName("正常系: CRITICAL優先度で第1段階ウィジェットのみ取得される")
        void getPersonalDashboard_CRITICAL_第1段階のみ() {
            // Given
            stubPersonalDashboardCommon();
            stubScopeCoverage();

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getGreeting()).isNotNull();
            assertThat(result.getGreeting().getMessage()).contains("テストユーザー");
            assertThat(result.getScopeCoverage()).isNotNull();
            // 第2段階はnull
            assertThat(result.getMyPosts()).isNull();
            assertThat(result.getUnreadThreads()).isNull();
            assertThat(result.getRecentActivity()).isNull();
            assertThat(result.getPersonalCalendar()).isNull();
        }

        @Test
        @DisplayName("正常系: ALL優先度で全ウィジェットが取得される")
        void getPersonalDashboard_ALL_全ウィジェット() {
            // Given
            stubPersonalDashboardCommon();
            stubScopeCoverage();

            given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(activityFeedService.getActivityFeed(eq(USER_ID), isNull(), anyInt(), any()))
                    .willReturn(List.of());

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getMyPosts()).isNotNull();
            assertThat(result.getUnreadThreads()).isNotNull();
            assertThat(result.getRecentActivity()).isNotNull();
            assertThat(result.getPersonalCalendar()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 未読通知がある場合にサマリーに件数が含まれる")
        void getPersonalDashboard_未読通知あり_サマリー含む() {
            // Given
            stubScopeCoverage();
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");

            // 未読通知3件
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(3L);
            given(notificationRepository.countByUserId(USER_ID)).willReturn(5L);
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result.getGreeting().getSummary()).contains("3件");
            assertThat(result.getNotices()).isNotNull();
            assertThat(result.getNotices().get("unread_count")).isEqualTo(3L);
        }

        @Test
        @DisplayName("正常系: 未読通知なしの場合にサマリーが適切に生成される")
        void getPersonalDashboard_未読通知なし_サマリー生成() {
            // Given
            stubPersonalDashboardCommon();
            stubScopeCoverage();

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result.getGreeting().getSummary()).contains("新しいお知らせはありません");
        }

        @Test
        @DisplayName("正常系: TODOに期限超過がある場合にoverdue_countが設定される")
        void getPersonalDashboard_TODO期限超過_overdueCount設定() {
            // Given
            stubScopeCoverage();
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);
            given(notificationRepository.countByUserId(USER_ID)).willReturn(0L);
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            TodoEntity overdueTodo = TodoEntity.builder()
                    .title("期限超過タスク")
                    .status(TodoStatus.IN_PROGRESS)
                    .priority(com.mannschaft.app.todo.TodoPriority.HIGH)
                    .dueDate(LocalDate.now().minusDays(1))
                    .build();
            TodoEntity activeTodo = TodoEntity.builder()
                    .title("アクティブタスク")
                    .status(TodoStatus.OPEN)
                    .priority(com.mannschaft.app.todo.TodoPriority.MEDIUM)
                    .dueDate(LocalDate.now().plusDays(3))
                    .build();
            TodoEntity completedTodo = TodoEntity.builder()
                    .title("完了済みタスク")
                    .status(TodoStatus.COMPLETED)
                    .priority(com.mannschaft.app.todo.TodoPriority.LOW)
                    .dueDate(LocalDate.now().minusDays(2))
                    .build();
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of(overdueTodo, activeTodo, completedTodo));

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result.getPersonalTodo()).isNotNull();
            assertThat(result.getPersonalTodo().get("overdue_count")).isEqualTo(1L);
            // 完了済みは除外されるので未完了は2件
            assertThat(result.getPersonalTodo().get("total_incomplete")).isEqualTo(2L);
        }

        @Test
        @DisplayName("正常系: 通知データがnoticesに含まれる")
        void getPersonalDashboard_通知データ_notices含む() {
            // Given
            stubScopeCoverage();
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(2L);
            given(notificationRepository.countByUserId(USER_ID)).willReturn(10L);

            NotificationEntity notification = NotificationEntity.builder()
                    .userId(USER_ID)
                    .notificationType("SCHEDULE_REMINDER")
                    .priority(NotificationPriority.NORMAL)
                    .title("テスト通知")
                    .body("テスト本文")
                    .sourceType("SCHEDULE")
                    .sourceId(1L)
                    .scopeType(NotificationScopeType.TEAM)
                    .scopeId(5L)
                    .actionUrl("/test")
                    .actorId(2L)
                    .build();
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of(notification)));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result.getNotices().get("unread_count")).isEqualTo(2L);
            assertThat(result.getNotices().get("total_count")).isEqualTo(10L);
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) result.getNotices().get("items");
            assertThat(items).hasSize(1);
        }

        @Test
        @DisplayName("正常系: スコープカバレッジに所属スコープ数が反映される")
        void getPersonalDashboard_スコープカバレッジ_スコープ数反映() {
            // Given
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);
            given(notificationRepository.countByUserId(USER_ID)).willReturn(0L);
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // 3チーム + 2組織に所属
            UserRoleEntity teamRole1 = UserRoleEntity.builder().userId(USER_ID).teamId(10L).roleId(1L).build();
            UserRoleEntity teamRole2 = UserRoleEntity.builder().userId(USER_ID).teamId(11L).roleId(1L).build();
            UserRoleEntity teamRole3 = UserRoleEntity.builder().userId(USER_ID).teamId(12L).roleId(1L).build();
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(teamRole1, teamRole2, teamRole3));
            UserRoleEntity orgRole1 = UserRoleEntity.builder().userId(USER_ID).organizationId(20L).roleId(1L).build();
            UserRoleEntity orgRole2 = UserRoleEntity.builder().userId(USER_ID).organizationId(21L).roleId(1L).build();
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID))
                    .willReturn(List.of(orgRole1, orgRole2));

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            assertThat(result.getScopeCoverage().getTotalScopes()).isEqualTo(5);
            assertThat(result.getScopeCoverage().getDisplayedScopes()).isEqualTo(5);
            assertThat(result.getScopeCoverage().isHasHiddenScopes()).isFalse();
        }

        @Test
        @DisplayName("正常系: ALL優先度でチャット未読数が集計される")
        void getPersonalDashboard_ALL_チャット未読数集計() {
            // Given
            stubPersonalDashboardCommon();
            stubScopeCoverage();

            given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());

            ChatChannelMemberEntity chatMember1 = ChatChannelMemberEntity.builder()
                    .userId(USER_ID).channelId(1L).unreadCount(3).build();
            ChatChannelMemberEntity chatMember2 = ChatChannelMemberEntity.builder()
                    .userId(USER_ID).channelId(2L).unreadCount(5).build();
            given(chatChannelMemberRepository.findByUserId(USER_ID))
                    .willReturn(List.of(chatMember1, chatMember2));
            given(activityFeedService.getActivityFeed(eq(USER_ID), isNull(), anyInt(), any()))
                    .willReturn(List.of());

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            // Then
            assertThat(result.getUnreadThreads()).isNotNull();
            assertThat(result.getUnreadThreads().get("total_unread_chat")).isEqualTo(8L);
        }
    }

    // ========================================
    // getTeamDashboard
    // ========================================

    @Nested
    @DisplayName("getTeamDashboard")
    class GetTeamDashboard {

        @Test
        @DisplayName("正常系: チームダッシュボードが取得される")
        void getTeamDashboard_正常_取得() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(5L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getWidgetSettings()).isNotNull();
            assertThat(result.getTeamBilling()).isNull(); // 非管理者はnull
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: 管理者の場合teamBillingが設定される")
        void getTeamDashboard_管理者_billing含む() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(true)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(5L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // Then
            assertThat(result.getTeamBilling()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 非メンバーがアクセスすると例外が発生する")
        void getTeamDashboard_非メンバー_例外() {
            // Given
            doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            // When / Then
            assertThatThrownBy(() -> dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("正常系: TODAY統計期間でチームダッシュボードが取得される")
        void getTeamDashboard_TODAY期間_取得() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(3L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "TODAY");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTeamActivity().get("total_members")).isEqualTo(3L);
        }

        @Test
        @DisplayName("正常系: MONTH統計期間でチームダッシュボードが取得される")
        void getTeamDashboard_MONTH期間_取得() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(3L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "MONTH");

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: null統計期間でWEEKがデフォルトになる")
        void getTeamDashboard_null期間_WEEKデフォルト() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(3L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, null);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: チームTODOに期限超過がある場合")
        void getTeamDashboard_チームTODO期限超過() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of());

            TodoEntity overdueTodo = TodoEntity.builder()
                    .title("期限超過")
                    .status(TodoStatus.IN_PROGRESS)
                    .priority(com.mannschaft.app.todo.TodoPriority.HIGH)
                    .dueDate(LocalDate.now().minusDays(1))
                    .build();
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of(overdueTodo)));

            given(timelinePostRepository.findFeedByScopeType(eq("TEAM"), eq(TEAM_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(3L);
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(any(), eq(TEAM_ID), any()))
                    .willReturn(new PageImpl<>(List.of()));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            TeamDashboardResponse result = dashboardService.getTeamDashboard(USER_ID, TEAM_ID, "WEEK");

            // Then
            assertThat(result.getTeamTodo().get("overdue_count")).isEqualTo(1L);
            assertThat(result.getTeamTodo().get("total_incomplete")).isEqualTo(1L);
        }
    }

    // ========================================
    // getOrgDashboard
    // ========================================

    @Nested
    @DisplayName("getOrgDashboard")
    class GetOrgDashboard {

        @Test
        @DisplayName("正常系: 組織ダッシュボードが取得される")
        void getOrgDashboard_正常_取得() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID), eq(false)))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(ORG_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(50L);
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(ORG_ID), any(), any()))
                    .willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            OrgDashboardResponse result = dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOrgStats()).isNotNull();
            assertThat(result.getOrgStats().get("total_members")).isEqualTo(50L);
            assertThat(result.getOrgBilling()).isNull(); // 非管理者
            verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("正常系: 管理者の場合orgBillingが設定される")
        void getOrgDashboard_管理者_billing含む() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID), eq(true)))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(ORG_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(50L);
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(ORG_ID), any(), any()))
                    .willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            OrgDashboardResponse result = dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK");

            // Then
            assertThat(result.getOrgBilling()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 非メンバーがアクセスすると例外が発生する")
        void getOrgDashboard_非メンバー_例外() {
            // Given
            doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

            // When / Then
            assertThatThrownBy(() -> dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("正常系: 組織TODOに期限超過がある場合にoverdue_countが設定される")
        void getOrgDashboard_TODO期限超過_overdueCount設定() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID), eq(false)))
                    .willReturn(List.of());

            TodoEntity overdueTodo = TodoEntity.builder()
                    .title("期限超過")
                    .status(TodoStatus.IN_PROGRESS)
                    .priority(com.mannschaft.app.todo.TodoPriority.HIGH)
                    .dueDate(LocalDate.now().minusDays(1))
                    .build();
            TodoEntity activeTodo = TodoEntity.builder()
                    .title("アクティブ")
                    .status(TodoStatus.OPEN)
                    .priority(com.mannschaft.app.todo.TodoPriority.MEDIUM)
                    .dueDate(LocalDate.now().plusDays(5))
                    .build();
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(ORG_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of(overdueTodo, activeTodo)));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(10L);
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(ORG_ID), any(), any()))
                    .willReturn(List.of());
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            OrgDashboardResponse result = dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK");

            // Then
            assertThat(result.getOrgTodo()).isNotNull();
            assertThat(result.getOrgTodo().get("overdue_count")).isEqualTo(1L);
            assertThat(result.getOrgTodo().get("total_incomplete")).isEqualTo(2L);
        }

        @Test
        @DisplayName("正常系: 組織スケジュールがorgNoticesに含まれる")
        void getOrgDashboard_スケジュール_orgNotices含む() {
            // Given
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID), eq(false)))
                    .willReturn(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(any(), eq(ORG_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(10L);

            ScheduleEntity schedule = ScheduleEntity.builder()
                    .title("組織イベント")
                    .startAt(LocalDateTime.now().plusDays(1))
                    .endAt(LocalDateTime.now().plusDays(1).plusHours(2))
                    .location("東京")
                    .allDay(false)
                    .build();
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(ORG_ID), any(), any()))
                    .willReturn(List.of(schedule));
            given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());

            // When
            OrgDashboardResponse result = dashboardService.getOrgDashboard(USER_ID, ORG_ID, "WEEK");

            // Then
            assertThat(result.getOrgNotices()).hasSize(1);
        }
    }
}
