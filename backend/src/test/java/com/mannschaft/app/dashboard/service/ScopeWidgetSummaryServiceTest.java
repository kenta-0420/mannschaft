package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F22.1 第二波: {@link ScopeWidgetSummaryService} の単体テスト（ブログ・カレンダー中心）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeWidgetSummaryService 単体テスト")
class ScopeWidgetSummaryServiceTest {

    @Mock
    private BlogPostRepository blogPostRepository;
    @Mock
    private com.mannschaft.app.chat.repository.ChatChannelRepository chatChannelRepository;
    @Mock
    private com.mannschaft.app.chat.repository.ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private com.mannschaft.app.timeline.repository.TimelinePostRepository timelinePostRepository;
    @Mock
    private com.mannschaft.app.bulletin.repository.BulletinThreadRepository bulletinThreadRepository;
    @Mock
    private com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository bulletinReadStatusRepository;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private ScopeWidgetSummaryService service;

    @Nested
    @DisplayName("buildLatestBlogPosts")
    class Blog {

        @Test
        @DisplayName("TEAM: 直近公開記事を author 名解決つきで返す")
        void team_blog() {
            BlogPostEntity post = BlogPostEntity.builder()
                    .teamId(10L).authorId(99L).title("お知らせ")
                    .status(PostStatus.PUBLISHED).publishedAt(LocalDateTime.now()).build();
            Page<BlogPostEntity> page = new PageImpl<>(List.of(post));
            given(blogPostRepository.findByTeamIdAndStatusOrderByPinnedDescPublishedAtDesc(
                    eq(10L), eq(PostStatus.PUBLISHED), any(Pageable.class))).willReturn(page);
            given(nameResolverService.resolveUserDisplayNames(List.of(99L)))
                    .willReturn(Map.of(99L, "山田太郎"));

            List<Map<String, Object>> result = service.buildLatestBlogPosts("TEAM", 10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("title")).isEqualTo("お知らせ");
            assertThat(result.get(0).get("author")).isEqualTo("山田太郎");
            assertThat(result.get(0)).containsKey("published_at");
        }

        @Test
        @DisplayName("記事なしは空配列を正直に返す")
        void blog_空() {
            given(blogPostRepository.findByOrganizationIdAndStatusOrderByPinnedDescPublishedAtDesc(
                    eq(20L), eq(PostStatus.PUBLISHED), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            List<Map<String, Object>> result = service.buildLatestBlogPosts("ORGANIZATION", 20L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("buildCalendarSummary")
    class Calendar {

        private ScheduleEntity event(LocalDateTime startAt, String title) {
            return ScheduleEntity.builder()
                    .teamId(10L).title(title).startAt(startAt)
                    .eventType(EventType.MEETING)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
        }

        @Test
        @DisplayName("days_with_events と next_event を集計する")
        void calendar_集計() {
            LocalDate today = LocalDate.now(ZoneId.of("UTC"));
            LocalDateTime e1 = today.atTime(10, 0);
            LocalDateTime e2 = today.plusDays(2).atTime(9, 0);
            // すべての scopeEvents 呼び出しに対し同じ 2 件を返す（件数・日集合・next を検証）
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(10L), any(), any()))
                    .willReturn(List.of(event(e1, "今日の会議"), event(e2, "明後日の会議")));

            Map<String, Object> result = service.buildCalendarSummary("TEAM", 10L, ZoneId.of("UTC"));

            assertThat(result).containsKeys("events_today", "events_this_week", "next_event", "days_with_events");
            @SuppressWarnings("unchecked")
            List<Integer> days = (List<Integer>) result.get("days_with_events");
            assertThat(days).contains(today.getDayOfMonth());
        }
    }

    @Nested
    @DisplayName("組織スコープの新規実装")
    class OrgScope {

        @Test
        @DisplayName("buildOrgLatestPosts: 実体なしは空配列を返す")
        void orgPosts_空() {
            given(timelinePostRepository.findFeedByScopeType(
                    eq(com.mannschaft.app.timeline.PostScopeType.ORGANIZATION), anyLong(), any(Pageable.class)))
                    .willReturn(List.of());

            assertThat(service.buildOrgLatestPosts(20L)).isEmpty();
        }

        @Test
        @DisplayName("buildOrgUpcomingEvents: 今後7日のイベントを返す")
        void orgUpcoming() {
            ScheduleEntity ev = ScheduleEntity.builder()
                    .organizationId(20L).title("総会").startAt(LocalDateTime.now().plusDays(1))
                    .eventType(EventType.EVENT).visibility(ScheduleVisibility.ORGANIZATION)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED).build();
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(eq(20L), any(), any()))
                    .willReturn(List.of(ev));

            List<Map<String, Object>> result = service.buildOrgUpcomingEvents(20L);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("title")).isEqualTo("総会");
        }

        @Test
        @DisplayName("buildOrgUnreadThreads: bulletin_threads(直近3件) と bulletin_count を同時に返す（AC-B4）")
        @SuppressWarnings("unchecked")
        void orgUnreadThreads_bulletinThreads() {
            var t1 = thread(1L, "組織スレA", false, LocalDateTime.of(2026, 7, 2, 0, 0));
            var t2 = thread(2L, "組織スレB", false, LocalDateTime.of(2026, 7, 1, 0, 0));
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                    eq(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION), eq(20L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(t1, t2)));
            given(bulletinReadStatusRepository.existsByThreadIdAndUserId(eq(1L), eq(7L))).willReturn(false);
            given(bulletinReadStatusRepository.existsByThreadIdAndUserId(eq(2L), eq(7L))).willReturn(true);
            // チャンネルなし（未読チャット 0）
            given(chatChannelRepository.findByOrganizationIdAndIsArchivedFalseOrderByLastMessageAtDesc(20L))
                    .willReturn(List.of());

            Map<String, Object> result = service.buildOrgUnreadThreads(20L, 7L);

            assertThat(result).containsKeys("bulletin_count", "chat_count", "bulletin_threads");
            assertThat(result.get("bulletin_count")).isEqualTo(1L); // t1 のみ未読
            List<Map<String, Object>> threads = (List<Map<String, Object>>) result.get("bulletin_threads");
            assertThat(threads).hasSize(2);
            assertThat(threads.get(0)).containsKeys("id", "title", "updated_at", "is_read");
            assertThat(threads.get(0).get("id")).isEqualTo(1L);
            assertThat(threads.get(0).get("is_read")).isEqualTo(false);
            assertThat(threads.get(1).get("is_read")).isEqualTo(true);
        }

        @Test
        @DisplayName("buildThreadListForScope: 直近3件をクエリ順で id/title/updated_at/is_read 付きで返す（AC-B2/B3）")
        @SuppressWarnings("unchecked")
        void threadList_直近3件() {
            var pinned = thread(1L, "固定", true, LocalDateTime.of(2026, 6, 1, 0, 0));
            var newest = thread(2L, "最新", false, LocalDateTime.of(2026, 7, 2, 0, 0));
            var mid = thread(3L, "中間", false, LocalDateTime.of(2026, 7, 1, 0, 0));
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                    eq(com.mannschaft.app.bulletin.ScopeType.TEAM), eq(10L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(pinned, newest, mid)));
            given(bulletinReadStatusRepository.existsByThreadIdAndUserId(anyLong(), eq(7L))).willReturn(false);

            List<Map<String, Object>> result = service.buildThreadListForScope("TEAM", 10L, 7L);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(m -> m.get("id")).containsExactly(1L, 2L, 3L);
            assertThat(result.get(0)).containsKeys("id", "title", "updated_at", "is_read");
        }

        @Test
        @DisplayName("buildThreadListForScope: スレッドなしは空配列を正直に返す")
        void threadList_空() {
            given(bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                    eq(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION), eq(20L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            assertThat(service.buildThreadListForScope("ORGANIZATION", 20L, 7L)).isEmpty();
        }

        private com.mannschaft.app.bulletin.entity.BulletinThreadEntity thread(
                Long id, String title, boolean pinned, LocalDateTime updatedAt) {
            return com.mannschaft.app.bulletin.entity.BulletinThreadEntity.builder()
                    .id(id)
                    .scopeType(com.mannschaft.app.bulletin.ScopeType.TEAM)
                    .scopeId(10L)
                    .title(title)
                    .body("body")
                    .isPinned(pinned)
                    .updatedAt(updatedAt)
                    .build();
        }
    }
}
