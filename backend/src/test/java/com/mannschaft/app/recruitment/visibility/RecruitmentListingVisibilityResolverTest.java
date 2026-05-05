package com.mannschaft.app.recruitment.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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
 * F00 Phase C — {@link RecruitmentListingVisibilityResolver} 単体テスト。
 *
 * <p>Repository / MembershipBatchQueryService をモック化し、本 Resolver と
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} の
 * 連携が機能 enum {@link RecruitmentVisibility} と {@link RecruitmentListingStatus}
 * に対して正しく動くことを網羅的に検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * Recruitment 固有の正規化（PUBLIC / SCOPE_ONLY / SUPPORTERS_ONLY / CUSTOM_TEMPLATE
 * × DRAFT/OPEN/FULL/CLOSED/COMPLETED/CANCELLED/AUTO_CANCELLED）のみを
 * 重点的に確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingVisibilityResolver — 単体テスト")
class RecruitmentListingVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RecruitmentListingRepository recruitmentListingRepository;

    private VisibilityMetrics visibilityMetrics;
    private RecruitmentListingVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new RecruitmentListingVisibilityResolver(
                membershipBatchQueryService,
                visibilityMetrics,
                templateEvaluator,
                null,            // FollowBatchService 不要
                auditLogService,
                recruitmentListingRepository);
    }

    @Test
    @DisplayName("referenceType() は RECRUITMENT_LISTING を返す")
    void referenceType_is_RECRUITMENT_LISTING() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.RECRUITMENT_LISTING);
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
            verifyNoInteractions(recruitmentListingRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(recruitmentListingRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
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
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isTrue();
            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SCOPE_ONLY × OPEN は所属メンバーのみ可視")
        void scope_only_open_visible_to_member() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SCOPE_ONLY × OPEN は非メンバーには不可視")
        void scope_only_open_invisible_to_non_member() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("SUPPORTERS_ONLY は SUPPORTER ロール所持者に可視")
        void supporters_only_visible_to_supporter() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SUPPORTERS_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "SUPPORTER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SUPPORTERS_ONLY は ADMIN にも可視（上位ロール包含）")
        void supporters_only_visible_to_admin() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SUPPORTERS_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("SUPPORTERS_ONLY は GUEST 相当（未認証）には不可視")
        void supporters_only_invisible_to_anonymous() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SUPPORTERS_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE は templateEvaluator の判定に従う（許可）")
        void custom_template_allowed_by_evaluator() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, 777L,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.CUSTOM_TEMPLATE);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(templateEvaluator.canView(eq(5L), eq(777L), eq(99L))).thenReturn(true);

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE は templateEvaluator が拒否すれば不可視")
        void custom_template_denied_by_evaluator() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, 777L,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.CUSTOM_TEMPLATE);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(templateEvaluator.canView(eq(5L), eq(777L), eq(99L))).thenReturn(false);

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は SCOPE_ONLY/SUPPORTERS_ONLY すべて可視")
        void system_admin_can_see_all() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SUPPORTERS_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
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
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 5L, null,
                    RecruitmentListingStatus.DRAFT, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は作成者以外には不可視（PUBLIC でも）")
        void draft_invisible_to_non_author() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.DRAFT, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin 以外不可視（ARCHIVED 扱い）")
        void cancelled_invisible_to_general_user() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.CANCELLED, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("AUTO_CANCELLED は SystemAdmin 以外不可視（ARCHIVED 扱い）")
        void auto_cancelled_invisible_to_general_user() {
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.AUTO_CANCELLED, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
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
            RecruitmentListingVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.CANCELLED, RecruitmentVisibility.PUBLIC);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("OPEN / FULL / CLOSED / COMPLETED はすべて PUBLISHED 相当")
        void published_aliases_visible_to_member() {
            for (RecruitmentListingStatus s : List.of(
                    RecruitmentListingStatus.OPEN,
                    RecruitmentListingStatus.FULL,
                    RecruitmentListingStatus.CLOSED,
                    RecruitmentListingStatus.COMPLETED)) {
                RecruitmentListingVisibilityProjection p = projection(
                        1L, "TEAM", 100L, 99L, null,
                        s, RecruitmentVisibility.SCOPE_ONLY);
                when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
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
            RecruitmentListingVisibilityProjection p1 = projection(
                    1L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.PUBLIC);
            RecruitmentListingVisibilityProjection p2 = projection(
                    2L, "TEAM", 100L, 99L, null,
                    RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY);
            when(recruitmentListingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 5L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(recruitmentListingRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static RecruitmentListingVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId,
            Long visibilityTemplateId,
            RecruitmentListingStatus status, RecruitmentVisibility visibility) {
        return new RecruitmentListingVisibilityProjection(
                id, scopeType, scopeId, authorUserId, visibilityTemplateId, status, visibility);
    }
}
