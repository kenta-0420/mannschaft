package com.mannschaft.app.dashboard;

import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link DashboardService} の追加単体テスト。
 * 実データを含む変換ロジックとプライベートヘルパーメソッドを検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardService 追加テスト")
class DashboardServiceAdditionalTest {

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

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    private void stubCommonPersonal() {
        given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.PERSONAL), eq(0L), eq(false)))
                .willReturn(List.of());
        given(nameResolverService.resolveUserDisplayName(USER_ID)).willReturn("テストユーザー");
        given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);
        given(notificationRepository.countByUserId(USER_ID)).willReturn(0L);
        given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                .willReturn(List.of());
        given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
        given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());
        given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());
        given(platformAnnouncementRepository.findActiveAnnouncements(any())).willReturn(List.of());
    }

    // ========================================
    // toNotificationMap - via getPersonalDashboard
    // ========================================

    @Nested
    @DisplayName("通知マップ変換テスト")
    class NotificationMapTests {

        @Test
        @DisplayName("正常系: 通知エンティティが正しいMapに変換される")
        void personalDashboard_通知エンティティ_正しいMapに変換() {
            // Given
            NotificationEntity notification = NotificationEntity.builder()
                    .userId(USER_ID)
                    .notificationType("BULLETIN_REPLY")
                    .title("新しい返信があります")
                    .body("掲示板に返信が追加されました")
                    .sourceType("BULLETIN_THREAD")
                    .sourceId(100L)
                    .scopeType(NotificationScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .actionUrl("/bulletin/threads/100")
                    .build();
            ReflectionTestUtils.setField(notification, "id", 42L);
            ReflectionTestUtils.setField(notification, "createdAt",
                    LocalDateTime.of(2026, 5, 1, 10, 0));

            stubCommonPersonal();
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of(notification)));

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> noticeItems =
                    (List<Map<String, Object>>) result.getNotices().get("items");
            assertThat(noticeItems).hasSize(1);
            assertThat(noticeItems.get(0).get("id")).isEqualTo(42L);
            assertThat(noticeItems.get(0).get("type")).isEqualTo("BULLETIN_REPLY");
            assertThat(noticeItems.get(0).get("title")).isEqualTo("新しい返信があります");
            assertThat(noticeItems.get(0).get("body")).isEqualTo("掲示板に返信が追加されました");
            assertThat(noticeItems.get(0).get("action_url")).isEqualTo("/bulletin/threads/100");
        }
    }

    // ========================================
    // toScheduleMap - via getPersonalDashboard
    // ========================================

    @Nested
    @DisplayName("スケジュールマップ変換テスト")
    class ScheduleMapTests {

        @Test
        @DisplayName("正常系: スケジュールエンティティが正しいMapに変換される")
        void personalDashboard_スケジュールエンティティ_正しいMapに変換() {
            // Given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("春季試合")
                    .location("東京スタジアム")
                    .startAt(LocalDateTime.of(2026, 5, 1, 14, 0))
                    .eventType(EventType.MATCH)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(schedule, "id", 55L);

            stubCommonPersonal();
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(List.of(schedule));

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upcomingEvents = (List<Map<String, Object>>) result.getUpcomingEvents();
            assertThat(upcomingEvents).hasSize(1);
            assertThat(upcomingEvents.get(0).get("id")).isEqualTo(55L);
            assertThat(upcomingEvents.get(0).get("title")).isEqualTo("春季試合");
            assertThat(upcomingEvents.get(0).get("location")).isEqualTo("東京スタジアム");
            assertThat(upcomingEvents.get(0).get("start_at")).isEqualTo(LocalDateTime.of(2026, 5, 1, 14, 0));
        }
    }

    // ========================================
    // toTimelinePostMap - via getPersonalDashboard ALL
    // ========================================

    @Nested
    @DisplayName("タイムライン投稿マップ変換テスト")
    class TimelinePostMapTests {

        @Test
        @DisplayName("正常系: タイムライン投稿が正しいMapに変換される")
        void personalDashboard_ALL_タイムライン投稿_正しいMapに変換() {
            // Given
            TimelinePostEntity post = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.PERSONAL)
                    .userId(USER_ID)
                    .content("テスト投稿内容")
                    .build();
            ReflectionTestUtils.setField(post, "id", 77L);
            ReflectionTestUtils.setField(post, "createdAt", LocalDateTime.of(2026, 5, 1, 12, 0));

            stubCommonPersonal();
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(timelinePostRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of(post));
            given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(activityFeedService.getActivityFeed(eq(USER_ID), any(), any(Integer.class), any()))
                    .willReturn(List.of());

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "ALL");

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> myPosts = (List<Map<String, Object>>) result.getMyPosts();
            assertThat(myPosts).hasSize(1);
            assertThat(myPosts.get(0).get("id")).isEqualTo(77L);
            assertThat(myPosts.get(0).get("content")).isEqualTo("テスト投稿内容");
            assertThat(myPosts.get(0).get("created_at")).isEqualTo(LocalDateTime.of(2026, 5, 1, 12, 0));
        }
    }

    // ========================================
    // upcomingItems > 10 branch
    // ========================================

    @Nested
    @DisplayName("スケジュール上限テスト")
    class ScheduleLimitTests {

        @Test
        @DisplayName("正常系: スケジュール11件以上は10件に切り詰められる")
        void personalDashboard_スケジュール11件_10件に切り詰め() {
            // Given
            List<ScheduleEntity> schedules = new java.util.ArrayList<>();
            for (int i = 0; i < 11; i++) {
                ScheduleEntity s = ScheduleEntity.builder()
                        .teamId(TEAM_ID)
                        .title("試合" + i)
                        .startAt(LocalDateTime.of(2026, 5, i + 1, 10, 0))
                        .eventType(EventType.MATCH)
                        .visibility(ScheduleVisibility.MEMBERS_ONLY)
                        .minViewRole(MinViewRole.MEMBER_PLUS)
                        .status(ScheduleStatus.SCHEDULED)
                        .build();
                ReflectionTestUtils.setField(s, "id", (long) i);
                schedules.add(s);
            }

            stubCommonPersonal();
            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(USER_ID), any(), any()))
                    .willReturn(schedules);

            // When
            PersonalDashboardResponse result = dashboardService.getPersonalDashboard(USER_ID, "CRITICAL");

            // Then
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upcomingEvents = (List<Map<String, Object>>) result.getUpcomingEvents();
            assertThat(upcomingEvents).hasSize(10);
        }
    }

    // ========================================
    // getTeamDashboard - toScheduleMap via teamUpcomingItems
    // ========================================

    @Nested
    @DisplayName("チームダッシュボード スケジュールマップ変換")
    class TeamDashboardScheduleMapTests {

        @Test
        @DisplayName("正常系: チームスケジュールが正しいMapに変換される")
        void teamDashboard_スケジュール実データ_正しいMapに変換() {
            // Given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("チーム練習")
                    .location("グラウンド")
                    .startAt(LocalDateTime.of(2026, 5, 2, 9, 0))
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(schedule, "id", 30L);

            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(widgetService.getWidgetSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID), eq(false)))
                    .willReturn(List.of());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(schedule));
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
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upcomingEvents =
                    (List<Map<String, Object>>) result.getTeamUpcomingEvents();
            assertThat(upcomingEvents).hasSize(1);
            assertThat(upcomingEvents.get(0).get("id")).isEqualTo(30L);
            assertThat(upcomingEvents.get(0).get("title")).isEqualTo("チーム練習");
            assertThat(upcomingEvents.get(0).get("location")).isEqualTo("グラウンド");
        }
    }
}
