package com.mannschaft.app.event.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase B — {@link EventVisibilityResolver} 単体テスト。
 *
 * <p>Repository / MembershipBatchQueryService をモック化し、本 Resolver と
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} の
 * 連携が機能 enum {@link EventVisibility} と {@link EventStatus} に対して正しく
 * 動くことを網羅的に検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * Event 固有の正規化（PUBLIC/MEMBERS_ONLY/SUPPORTERS_AND_ABOVE × DRAFT/PUBLISHED/CANCELLED 等）
 * のみを重点的に確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventVisibilityResolver — 単体テスト")
class EventVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private EventRepository eventRepository;

    private VisibilityMetrics visibilityMetrics;
    private EventVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new EventVisibilityResolver(
                membershipBatchQueryService,
                visibilityMetrics,
                templateEvaluator,
                null,            // FollowBatchService 不要
                auditLogService,
                eventRepository);
    }

    @Test
    @DisplayName("referenceType() は EVENT を返す")
    void referenceType_is_EVENT() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.EVENT);
    }

    // -------------------------------------------------------------------------
    // 入口ガード
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("入口ガード")
    class EntryGuard {

        @Test
        @DisplayName("contentId=null は false")
        void canView_nullId_false() {
            assertThat(resolver.canView(null, 1L)).isFalse();
            verifyNoInteractions(eventRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(eventRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(eventRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    // -------------------------------------------------------------------------
    // visibility 評価
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("visibility 評価")
    class VisibilityEvaluation {

        @Test
        @DisplayName("PUBLIC × PUBLISHED は誰でも閲覧可（匿名含む）")
        void public_published_visible_to_anyone() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.PUBLIC);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isTrue();
            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY × PUBLISHED は所属メンバーのみ可視")
        void members_only_published_visible_to_member() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.MEMBERS_ONLY);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY × PUBLISHED は非メンバーには不可視")
        void members_only_published_invisible_to_non_member() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.MEMBERS_ONLY);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE は SUPPORTER ロール所持者に可視")
        void supporters_and_above_visible_to_supporter() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.SUPPORTERS_AND_ABOVE);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "SUPPORTER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE は ADMIN にも可視（上位ロール包含）")
        void supporters_and_above_visible_to_admin() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.SUPPORTERS_AND_ABOVE);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SystemAdmin は MEMBERS_ONLY/SUPPORTERS_AND_ABOVE すべて可視")
        void system_admin_can_see_all() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.SUPPORTERS_AND_ABOVE);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // status 軸ガード
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("status 軸ガード")
    class StatusGuard {

        @Test
        @DisplayName("DRAFT は作成者本人に可視")
        void draft_visible_to_author() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 5L,
                    EventStatus.DRAFT, EventVisibility.PUBLIC);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は作成者以外には不可視（PUBLIC でも）")
        void draft_invisible_to_non_author() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.DRAFT, EventVisibility.PUBLIC);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin 以外不可視（ARCHIVED 扱い）")
        void cancelled_invisible_to_general_user() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.CANCELLED, EventVisibility.PUBLIC);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin に可視")
        void cancelled_visible_to_system_admin() {
            EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.CANCELLED, EventVisibility.PUBLIC);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("REGISTRATION_OPEN / REGISTRATION_CLOSED / IN_PROGRESS / COMPLETED はすべて PUBLISHED 相当")
        void published_aliases_visible_to_member() {
            for (EventStatus s : List.of(
                    EventStatus.PUBLISHED,
                    EventStatus.REGISTRATION_OPEN,
                    EventStatus.REGISTRATION_CLOSED,
                    EventStatus.IN_PROGRESS,
                    EventStatus.COMPLETED)) {
                EventVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                        s, EventVisibility.MEMBERS_ONLY);
                when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                        .thenReturn(List.of(p));
                when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                        .thenReturn(new UserScopeRoleSnapshot(false,
                                Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                                Map.of(), Set.of(), Set.of()));

                assertThat(resolver.canView(1L, 5L))
                        .as("status=%s は PUBLISHED 相当として可視", s)
                        .isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // バッチ呼び出し
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("バッチ呼び出し")
    class Batch {

        @Test
        @DisplayName("filterAccessible は Repository を 1 回・MembershipBatchQueryService を 1 回呼ぶ")
        void single_call_for_batch() {
            EventVisibilityProjection p1 = projection(1L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.PUBLIC);
            EventVisibilityProjection p2 = projection(2L, "TEAM", 100L, 99L,
                    EventStatus.PUBLISHED, EventVisibility.MEMBERS_ONLY);
            when(eventRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 5L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(eventRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static EventVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId,
            EventStatus status, EventVisibility visibility) {
        return new EventVisibilityProjection(
                id, scopeType, scopeId, authorUserId, status, visibility);
    }
}
