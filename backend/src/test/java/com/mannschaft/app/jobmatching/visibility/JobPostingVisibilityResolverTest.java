package com.mannschaft.app.jobmatching.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
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
 * F00 Phase C — {@link JobPostingVisibilityResolver} 単体テスト。
 *
 * <p>Repository / MembershipBatchQueryService をモック化し、本 Resolver と
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} の
 * 連携が機能 enum {@link VisibilityScope} と {@link JobPostingStatus} に対して正しく
 * 動くことを網羅的に検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * JobPosting 固有の正規化（VisibilityScope 全 6 値 × DRAFT/OPEN/CLOSED/CANCELLED）と
 * §5.1.4 CUSTOM 個別処理（{@link VisibilityScope#JOBBER_INTERNAL}）の網羅に集中する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobPostingVisibilityResolver — 単体テスト")
class JobPostingVisibilityResolverTest {

    private static final Long TEAM_ID = 100L;
    private static final ScopeKey TEAM_SCOPE = new ScopeKey("TEAM", TEAM_ID);
    private static final Long AUTHOR_ID = 99L;
    private static final Long VIEWER_ID = 5L;

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private JobPostingRepository jobPostingRepository;

    private VisibilityMetrics visibilityMetrics;
    private JobPostingVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new JobPostingVisibilityResolver(
                membershipBatchQueryService,
                visibilityMetrics,
                templateEvaluator,
                null,            // FollowBatchService 不要
                auditLogService,
                jobPostingRepository);
    }

    @Test
    @DisplayName("referenceType() は JOB_POSTING を返す")
    void referenceType_is_JOB_POSTING() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.JOB_POSTING);
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
            assertThat(resolver.canView(null, VIEWER_ID)).isFalse();
            verifyNoInteractions(jobPostingRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), VIEWER_ID)).isEmpty();
            verifyNoInteractions(jobPostingRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, VIEWER_ID)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    // -------------------------------------------------------------------------
    // visibility 評価（標準 Mapper 結線）
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("visibility 評価 — 標準 Mapper 結線")
    class VisibilityEvaluation {

        @Test
        @DisplayName("JOBBER_PUBLIC_BOARD × OPEN は誰でも閲覧可（匿名含む、PUBLIC 相当）")
        void jobber_public_board_visible_to_anyone() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isTrue();
            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("TEAM_MEMBERS × OPEN は所属メンバーのみ可視")
        void team_members_visible_to_member() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.TEAM_MEMBERS);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("MEMBER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("TEAM_MEMBERS × OPEN は非メンバーには不可視")
        void team_members_invisible_to_non_member() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.TEAM_MEMBERS);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("TEAM_MEMBERS_SUPPORTERS × OPEN は SUPPORTER に可視")
        void supporters_scope_visible_to_supporter() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.TEAM_MEMBERS_SUPPORTERS);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("SUPPORTER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("ORGANIZATION_SCOPE × OPEN は親 ORG 所属に可視")
        void organization_scope_visible_to_parent_org_member() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.ORGANIZATION_SCOPE);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            UserScopeRoleSnapshot snap = new UserScopeRoleSnapshot(false,
                    Map.of(),
                    Map.of(TEAM_SCOPE, 200L),
                    Set.of(new ScopeKey("ORGANIZATION", 200L)),
                    Set.of());
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snap);

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("SystemAdmin はあらゆる visibility が可視（高速パス）")
        void system_admin_can_see_all_scopes() {
            for (VisibilityScope scope : VisibilityScope.values()) {
                if (scope == VisibilityScope.CUSTOM_TEMPLATE) {
                    // CUSTOM_TEMPLATE は visibility_template_id 必須のため別系統で扱う
                    continue;
                }
                JobPostingVisibilityProjection p = projection(1L,
                        JobPostingStatus.OPEN, scope);
                when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                        .thenReturn(List.of(p));
                when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                        .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

                assertThat(resolver.canView(1L, VIEWER_ID))
                        .as("VisibilityScope=%s × OPEN は SystemAdmin に可視", scope)
                        .isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // §5.1.4 CUSTOM 個別処理（JOBBER_INTERNAL）
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("§5.1.4 CUSTOM — JOBBER_INTERNAL 判定")
    class JobberInternalCustom {

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は当該チームの JOBBER ロール保有者に可視")
        void jobber_internal_visible_to_jobber() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("JOBBER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は MEMBER ロールには不可視（JOBBER でなければ拒否）")
        void jobber_internal_invisible_to_plain_member() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("MEMBER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は SUPPORTER ロールには不可視")
        void jobber_internal_invisible_to_supporter() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("SUPPORTER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は ADMIN ロールには不可視（JOBBER と並行ロール、§5.2 備考）")
        void jobber_internal_invisible_to_admin() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("ADMIN"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は非所属ユーザーには不可視")
        void jobber_internal_invisible_to_non_member() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は匿名ユーザーには不可視")
        void jobber_internal_invisible_to_anonymous() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL × OPEN は SystemAdmin には可視（基底高速パス）")
        void jobber_internal_visible_to_system_admin() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("JOBBER_INTERNAL は別チームの JOBBER ロールでは可視にならない（チームごと独立）")
        void jobber_internal_isolated_per_team() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            // 別チーム (200) で JOBBER ロール保有 → 対象チーム (100) では JOBBER でない
            UserScopeRoleSnapshot snap = new UserScopeRoleSnapshot(false,
                    Map.of(new ScopeKey("TEAM", 200L), "JOBBER"),
                    Map.of(), Set.of(), Set.of());
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snap);

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // status 軸ガード（JobPostingStatus → ContentStatus 写像）
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("status 軸ガード — JobPostingStatus 写像")
    class StatusGuard {

        @Test
        @DisplayName("DRAFT は作成者本人に可視（PUBLIC でも author/SysAdmin のみ）")
        void draft_visible_to_author() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.DRAFT, VisibilityScope.JOBBER_PUBLIC_BOARD,
                    /*author=*/ VIEWER_ID);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は作成者以外には不可視（公開範囲 PUBLIC 相当でも）")
        void draft_invisible_to_non_author() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.DRAFT, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("OPEN は PUBLISHED 相当として visibility 評価へ進む")
        void open_treated_as_published() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("CLOSED は PUBLISHED 相当として visibility 評価へ進む（応募終了後も閲覧可）")
        void closed_treated_as_published() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.CLOSED, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin 以外不可視（ARCHIVED 扱い）")
        void cancelled_invisible_to_general_user() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.CANCELLED, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("MEMBER"));

            assertThat(resolver.canView(1L, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin に可視")
        void cancelled_visible_to_system_admin() {
            JobPostingVisibilityProjection p = projection(1L,
                    JobPostingStatus.CANCELLED, VisibilityScope.JOBBER_PUBLIC_BOARD);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, VIEWER_ID)).isTrue();
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
            JobPostingVisibilityProjection p1 = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_PUBLIC_BOARD);
            JobPostingVisibilityProjection p2 = projection(2L,
                    JobPostingStatus.OPEN, VisibilityScope.TEAM_MEMBERS);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("MEMBER"));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), VIEWER_ID);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(jobPostingRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(VIEWER_ID), anySet(), anySet());
        }

        @Test
        @DisplayName("filterAccessible は visibility 不一致 ID をフィルタアウトする")
        void filter_out_invisible_in_batch() {
            // p1: 誰でも可視 / p2: JOBBER 限定 / p3: TEAM_MEMBERS
            JobPostingVisibilityProjection p1 = projection(1L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_PUBLIC_BOARD);
            JobPostingVisibilityProjection p2 = projection(2L,
                    JobPostingStatus.OPEN, VisibilityScope.JOBBER_INTERNAL);
            JobPostingVisibilityProjection p3 = projection(3L,
                    JobPostingStatus.OPEN, VisibilityScope.TEAM_MEMBERS);
            when(jobPostingRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2, p3));
            // viewer は MEMBER のみ → p1 (PUBLIC 相当) と p3 (MEMBER) が可視、p2 (JOBBER) は不可視
            when(membershipBatchQueryService.snapshotForUser(eq(VIEWER_ID), anySet(), anySet()))
                    .thenReturn(snapshotWithRole("MEMBER"));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L, 3L), VIEWER_ID);

            assertThat(result).containsExactlyInAnyOrder(1L, 3L);
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static JobPostingVisibilityProjection projection(
            Long id, JobPostingStatus status, VisibilityScope visibility) {
        return projection(id, status, visibility, AUTHOR_ID);
    }

    private static JobPostingVisibilityProjection projection(
            Long id, JobPostingStatus status, VisibilityScope visibility, Long authorUserId) {
        return new JobPostingVisibilityProjection(
                id, "TEAM", TEAM_ID, authorUserId, status, visibility);
    }

    private static UserScopeRoleSnapshot snapshotWithRole(String role) {
        return new UserScopeRoleSnapshot(false,
                Map.of(TEAM_SCOPE, role),
                Map.of(), Set.of(), Set.of());
    }
}
