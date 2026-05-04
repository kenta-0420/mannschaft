package com.mannschaft.app.cms.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.DenyReason;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityDecision;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * {@link BlogPostVisibilityResolver} の単体テスト。
 *
 * <p>F00 Phase B BlogPost — Resolver の以下を検証する:
 * <ul>
 *   <li>{@link ReferenceType#BLOG_POST} を返す</li>
 *   <li>{@link Visibility} → {@link StandardVisibility} の正規化</li>
 *   <li>{@link PostStatus} → {@link ContentStatus} の正規化</li>
 *   <li>scope (TEAM/ORGANIZATION/個人ブログ) 決定ロジック</li>
 *   <li>基底クラスとの結合 (PUBLIC / MEMBERS_ONLY / PRIVATE / SystemAdmin / status ガード)</li>
 * </ul>
 *
 * <p>{@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * の判定パイプライン全網羅は本クラスの責務外（{@code AbstractContentVisibilityResolverTest}
 * が 38 件で網羅済み）。本クラスは BlogPost 固有部分とエンドツーエンドの結線確認に絞る。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostVisibilityResolver — F00 Phase B")
class BlogPostVisibilityResolverTest {

    @Mock
    private BlogPostRepository blogPostRepository;
    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;
    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;
    @Mock
    private FollowBatchService followBatchService;
    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private BlogPostVisibilityResolver resolver;

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long AUTHOR_ID = 1L;
    private static final Long VIEWER_ID = 2L;
    private static final Long ADMIN_ID = 99L;
    private static final Long POST_ID = 10L;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new BlogPostVisibilityResolver(
                blogPostRepository,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService);
    }

    private BlogPostVisibilityProjection projection(
            Long id, Long teamId, Long organizationId,
            Visibility visibility, PostStatus status) {
        return BlogPostVisibilityProjection.of(
                id, teamId, organizationId, AUTHOR_ID, null, visibility, status);
    }

    // ========================================================================
    // referenceType
    // ========================================================================

    @Test
    @DisplayName("referenceType() は BLOG_POST を返す")
    void referenceType_blogPost() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.BLOG_POST);
    }

    // ========================================================================
    // BlogPostVisibilityProjection — scope 決定
    // ========================================================================

    @Nested
    @DisplayName("BlogPostVisibilityProjection — scope 決定ロジック")
    class ProjectionScopeRules {

        @Test
        @DisplayName("teamId 指定 → scopeType=TEAM, scopeId=teamId")
        void teamScope() {
            BlogPostVisibilityProjection p = BlogPostVisibilityProjection.of(
                    POST_ID, TEAM_ID, null, AUTHOR_ID, null,
                    Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            assertThat(p.scopeType()).isEqualTo("TEAM");
            assertThat(p.scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("organizationId 指定 → scopeType=ORGANIZATION, scopeId=organizationId")
        void orgScope() {
            BlogPostVisibilityProjection p = BlogPostVisibilityProjection.of(
                    POST_ID, null, ORG_ID, AUTHOR_ID, null,
                    Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            assertThat(p.scopeType()).isEqualTo("ORGANIZATION");
            assertThat(p.scopeId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("teamId/orgId 両方 null (個人ブログ) → scopeType=null, scopeId=null")
        void personalBlogScope() {
            BlogPostVisibilityProjection p = BlogPostVisibilityProjection.of(
                    POST_ID, null, null, AUTHOR_ID, null,
                    Visibility.PRIVATE, PostStatus.PUBLISHED);
            assertThat(p.scopeType()).isNull();
            assertThat(p.scopeId()).isNull();
        }

        @Test
        @DisplayName("teamId 優先: teamId と organizationId 両方非 null なら teamId 採用")
        void teamPreferredOverOrg() {
            BlogPostVisibilityProjection p = BlogPostVisibilityProjection.of(
                    POST_ID, TEAM_ID, ORG_ID, AUTHOR_ID, null,
                    Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            assertThat(p.scopeType()).isEqualTo("TEAM");
            assertThat(p.scopeId()).isEqualTo(TEAM_ID);
        }
    }

    // ========================================================================
    // canView 結合 — PUBLIC / MEMBERS_ONLY / PRIVATE
    // ========================================================================

    @Nested
    @DisplayName("canView 結合 — 基本パス")
    class CanViewIntegration {

        @Test
        @DisplayName("PUBLIC + PUBLISHED は未認証ユーザーでも可視")
        void publicPublished_anonymous_allowed() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            // 匿名ユーザー → snapshot は empty
            when(membershipBatchQueryService.snapshotForUser(
                    any(), any(), any())).thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, null)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY + PUBLISHED + メンバー → 可視")
        void membersOnly_member_allowed() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                    false,
                    Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                    Map.of(),
                    Set.of(),
                    Set.of());
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(snapshot);

            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_ONLY + 非メンバー → 不可視")
        void membersOnly_nonMember_denied() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("PRIVATE は作成者のみ可視")
        void privatePublished_authorOnly() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, null, null, Visibility.PRIVATE, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, AUTHOR_ID)).isTrue();
            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin 高速パス: PUBLISHED 記事は visibility に関わらず可視")
        void systemAdmin_publishedAlwaysAllowed() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(POST_ID, ADMIN_ID)).isTrue();
        }

        @Test
        @DisplayName("実存しない ID → 空 Set (loadProjections が空)")
        void notFound_empty() {
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of());

            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isFalse();
        }
    }

    // ========================================================================
    // status × visibility 合成 (§7.5)
    // ========================================================================

    @Nested
    @DisplayName("status ガード (§7.5)")
    class StatusGuard {

        @Test
        @DisplayName("DRAFT + PUBLIC: 作成者は可視")
        void draft_authorAllowed() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.DRAFT);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, AUTHOR_ID)).isTrue();
        }

        @Test
        @DisplayName("DRAFT + PUBLIC: 第三者は不可視")
        void draft_othersDenied() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.DRAFT);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("PENDING_REVIEW は DRAFT 扱い (作成者のみ可視)")
        void pendingReview_authorOnly() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.PENDING_REVIEW);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, AUTHOR_ID)).isTrue();
            assertThat(resolver.canView(POST_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("REJECTED は DRAFT 扱い (作成者のみ可視)")
        void rejected_authorOnly() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.REJECTED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(POST_ID, AUTHOR_ID)).isTrue();
        }

        @Test
        @DisplayName("ARCHIVED は SystemAdmin のみ可視")
        void archived_systemAdminOnly() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.PUBLIC, PostStatus.ARCHIVED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            // 作成者でも不可視
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            assertThat(resolver.canView(POST_ID, AUTHOR_ID)).isFalse();

            // SystemAdmin だけ可視
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());
            assertThat(resolver.canView(POST_ID, ADMIN_ID)).isTrue();
        }
    }

    // ========================================================================
    // filterAccessible — バッチ判定
    // ========================================================================

    @Nested
    @DisplayName("filterAccessible — バッチ判定")
    class BatchFilter {

        @Test
        @DisplayName("PUBLIC と MEMBERS_ONLY 混在 → メンバーは PUBLIC + MEMBERS_ONLY 両方可視")
        void mixedVisibility_member() {
            BlogPostVisibilityProjection p1 = projection(
                    1L, TEAM_ID, null, Visibility.PUBLIC, PostStatus.PUBLISHED);
            BlogPostVisibilityProjection p2 = projection(
                    2L, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            BlogPostVisibilityProjection p3 = projection(
                    3L, TEAM_ID, null, Visibility.PRIVATE, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2, p3));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L, 3L), VIEWER_ID);
            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
        }
    }

    // ========================================================================
    // decide — DenyReason の分類
    // ========================================================================

    @Nested
    @DisplayName("decide — DenyReason 分類")
    class DecideClassification {

        @Test
        @DisplayName("実存しない ID は NOT_FOUND")
        void notFound() {
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of());

            VisibilityDecision decision = resolver.decide(POST_ID, VIEWER_ID);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
        }

        @Test
        @DisplayName("PRIVATE 不可視 → NOT_OWNER")
        void privateDeniedNotOwner() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, null, null, Visibility.PRIVATE, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision decision = resolver.decide(POST_ID, VIEWER_ID);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_OWNER);
            assertThat(decision.resolvedLevel()).isEqualTo(StandardVisibility.PRIVATE);
        }

        @Test
        @DisplayName("MEMBERS_ONLY 非メンバー → NOT_A_MEMBER")
        void membersOnlyNotMember() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision decision = resolver.decide(POST_ID, VIEWER_ID);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_A_MEMBER);
            assertThat(decision.resolvedLevel()).isEqualTo(StandardVisibility.MEMBERS_ONLY);
        }

        @Test
        @DisplayName("allow 時の Decision に resolvedLevel が載る")
        void allowedDecisionResolvedLevel() {
            BlogPostVisibilityProjection p = projection(
                    POST_ID, TEAM_ID, null, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
            when(blogPostRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            VisibilityDecision decision = resolver.decide(POST_ID, VIEWER_ID);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.resolvedLevel()).isEqualTo(StandardVisibility.MEMBERS_ONLY);
        }
    }
}
