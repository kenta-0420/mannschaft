package com.mannschaft.app.tournament.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.repository.TournamentRepository;
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
 * F00 Phase C — {@link TournamentVisibilityResolver} 単体テスト。
 *
 * <p>Repository / MembershipBatchQueryService をモック化し、本 Resolver と
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} の
 * 連携が機能 enum {@link TournamentVisibility} と {@link TournamentStatus} に対して
 * 正しく動くことを検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * Tournament 固有の正規化（PUBLIC/MEMBERS_ONLY × DRAFT/OPEN/IN_PROGRESS/COMPLETED/CANCELLED/ARCHIVED）
 * のみを重点的に確認する。Tournament は組織配下のため scope は常に "ORGANIZATION"。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentVisibilityResolver — 単体テスト")
class TournamentVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private TournamentRepository tournamentRepository;

    private VisibilityMetrics visibilityMetrics;
    private TournamentVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new TournamentVisibilityResolver(
                membershipBatchQueryService,
                visibilityMetrics,
                templateEvaluator,
                null,            // FollowBatchService 不要
                auditLogService,
                tournamentRepository);
    }

    @Test
    @DisplayName("referenceType() は TOURNAMENT を返す")
    void referenceType_is_TOURNAMENT() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.TOURNAMENT);
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
            verifyNoInteractions(tournamentRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(tournamentRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(tournamentRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
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
        @DisplayName("PUBLIC × OPEN は誰でも閲覧可（匿名含む）")
        void public_open_visible_to_anyone() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.OPEN, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isTrue();
            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY × IN_PROGRESS は所属メンバーのみ可視")
        void members_only_in_progress_visible_to_member() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.IN_PROGRESS, TournamentVisibility.MEMBERS_ONLY);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("ORGANIZATION", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY × COMPLETED は非メンバーには不可視")
        void members_only_completed_invisible_to_non_member() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.COMPLETED, TournamentVisibility.MEMBERS_ONLY);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は MEMBERS_ONLY もすべて可視")
        void system_admin_can_see_all() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.OPEN, TournamentVisibility.MEMBERS_ONLY);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
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
            TournamentVisibilityProjection p = projection(1L, 100L, 5L,
                    TournamentStatus.DRAFT, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は作成者以外には不可視（PUBLIC でも）")
        void draft_invisible_to_non_author() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.DRAFT, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin 以外不可視（ARCHIVED 扱い）")
        void cancelled_invisible_to_general_user() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.CANCELLED, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("ORGANIZATION", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin に可視")
        void cancelled_visible_to_system_admin() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.CANCELLED, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("ARCHIVED は CANCELLED と同じく ARCHIVED 扱い → SystemAdmin のみ可視")
        void archived_visible_only_to_system_admin() {
            TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                    TournamentStatus.ARCHIVED, TournamentVisibility.PUBLIC);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("OPEN / IN_PROGRESS / COMPLETED はすべて PUBLISHED 相当")
        void published_aliases_visible_to_member() {
            for (TournamentStatus s : List.of(
                    TournamentStatus.OPEN,
                    TournamentStatus.IN_PROGRESS,
                    TournamentStatus.COMPLETED)) {
                TournamentVisibilityProjection p = projection(1L, 100L, 99L,
                        s, TournamentVisibility.MEMBERS_ONLY);
                when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                        .thenReturn(List.of(p));
                when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                        .thenReturn(new UserScopeRoleSnapshot(false,
                                Map.of(new ScopeKey("ORGANIZATION", 100L), "MEMBER"),
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
            TournamentVisibilityProjection p1 = projection(1L, 100L, 99L,
                    TournamentStatus.OPEN, TournamentVisibility.PUBLIC);
            TournamentVisibilityProjection p2 = projection(2L, 100L, 99L,
                    TournamentStatus.IN_PROGRESS, TournamentVisibility.MEMBERS_ONLY);
            when(tournamentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("ORGANIZATION", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 5L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(tournamentRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static TournamentVisibilityProjection projection(
            Long id, Long orgId, Long authorUserId,
            TournamentStatus status, TournamentVisibility visibility) {
        return new TournamentVisibilityProjection(
                id, "ORGANIZATION", orgId, authorUserId, status, visibility);
    }
}
