package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AbstractContentVisibilityResolver} の単体テスト。
 *
 * <p>F00 Phase A-1c — Resolver 共通テンプレートの判定パイプライン
 * （SystemAdmin 高速パス・status ガード・親 ORG 連鎖・各 StandardVisibility 値・
 * 監査ログ・メトリクス）を、サブクラスのモック実装を使って網羅的に検証する。</p>
 *
 * <p>サブクラス {@link FakeResolver} は本テスト内に閉じた最小実装で、
 * loadProjections / toStandard / toContentStatus / evaluateCustom を
 * フィールドで差し替え可能にしてある。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractContentVisibilityResolver — Resolver 共通テンプレート")
class AbstractContentVisibilityResolverTest {

    private static final ReferenceType REF_TYPE = ReferenceType.BLOG_POST;

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private FollowBatchService followBatchService;

    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private MeterRegistry meterRegistry;

    private FakeResolver resolver;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        visibilityMetrics = new VisibilityMetrics(meterRegistry);
        resolver = new FakeResolver(
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService);
    }

    // ========================================================================
    // canView / filterAccessible 入口
    // ========================================================================

    @Nested
    @DisplayName("入口ガード — null/空入力")
    class EntryGuard {

        @Test
        @DisplayName("contentId=null の canView は false を返し DB アクセスしない")
        void canView_nullId_false() {
            assertThat(resolver.canView(null, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空コレクションの filterAccessible は空 Set を返し DB アクセスしない")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("loadProjections が空を返したら空 Set（IDOR 防止）")
        void filterAccessible_noProjection_empty() {
            resolver.projections = List.of();
            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 1L);
            assertThat(result).isEmpty();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    // ========================================================================
    // SystemAdmin 高速パス（§15 D-13）
    // ========================================================================

    @Nested
    @DisplayName("SystemAdmin 高速パス（§15 D-13）")
    class SystemAdminFastPath {

        @Test
        @DisplayName("SystemAdmin は PUBLISHED なら全 visibility で可視")
        void sysAdmin_published_all_allowed() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(eq(1L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            Set<Long> result = resolver.filterAccessible(List.of(1L), 1L);
            assertThat(result).containsExactly(1L);
        }

        @Test
        @DisplayName("SystemAdmin でも DELETED は不可視（fail-closed）")
        void sysAdmin_deleted_blocked() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.DELETED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.filterAccessible(List.of(1L), 1L)).isEmpty();
        }

        @Test
        @DisplayName("SystemAdmin は ARCHIVED も可視")
        void sysAdmin_archived_allowed() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.ARCHIVED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.filterAccessible(List.of(1L), 1L)).containsExactly(1L);
        }
    }

    // ========================================================================
    // status ガード（§7.5）
    // ========================================================================

    @Nested
    @DisplayName("status ガード（§7.5）")
    class StatusGuard {

        @Test
        @DisplayName("ARCHIVED は SystemAdmin 以外不可視")
        void archived_blocked_for_non_admin() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.ARCHIVED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
        }

        @Test
        @DisplayName("DRAFT は作成者本人のみ可視")
        void draft_visible_only_to_author() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.DRAFT;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 999L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
        }

        @Test
        @DisplayName("SCHEDULED は作成者本人または SystemAdmin のみ可視")
        void scheduled_author_or_sysadmin() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.SCHEDULED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 999L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
        }

        @Test
        @DisplayName("DELETED は誰も可視ではない（未認証含む）")
        void deleted_invisible_to_all() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.DELETED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 999L)).isEmpty();
            assertThat(resolver.filterAccessible(List.of(1L), null)).isEmpty();
        }
    }

    // ========================================================================
    // visibility 評価（§5.1 / §11.6）
    // ========================================================================

    @Nested
    @DisplayName("visibility 評価")
    class VisibilityEvaluation {

        @Test
        @DisplayName("PUBLIC は未認証でも可視")
        void publicVisibility_anon() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(isNull(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), null)).containsExactly(1L);
        }

        @Test
        @DisplayName("MEMBERS_ONLY はスコープ所属者のみ可視")
        void membersOnly_member_only() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.MEMBERS_ONLY);
            resolver.projections = List.of(p);
            ScopeKey scope = new ScopeKey("TEAM", 100L);
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "MEMBER"), Map.of(), Set.of(), Set.of()));
            when(membershipBatchQueryService.snapshotForUser(eq(6L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE は SUPPORTER 以上のみ可視")
        void supportersAndAbove() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.SUPPORTERS_AND_ABOVE);
            resolver.projections = List.of(p);
            ScopeKey scope = new ScopeKey("TEAM", 100L);
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "SUPPORTER"), Map.of(), Set.of(), Set.of()));
            when(membershipBatchQueryService.snapshotForUser(eq(6L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "GUEST"), Map.of(), Set.of(), Set.of()));

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("ADMINS_ONLY は ADMIN 以上のみ可視")
        void adminsOnly() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.ADMINS_ONLY);
            resolver.projections = List.of(p);
            ScopeKey scope = new ScopeKey("TEAM", 100L);
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "ADMIN"), Map.of(), Set.of(), Set.of()));
            when(membershipBatchQueryService.snapshotForUser(eq(6L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "SUPPORTER"), Map.of(), Set.of(), Set.of()));

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("PRIVATE は作成者本人のみ可視")
        void privateVisibility() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 999L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
            assertThat(resolver.filterAccessible(List.of(1L), null)).isEmpty();
        }

        @Test
        @DisplayName("ORGANIZATION_WIDE は親 ORG 所属者のみ可視")
        void organizationWide() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.ORGANIZATION_WIDE);
            resolver.projections = List.of(p);
            ScopeKey teamScope = new ScopeKey("TEAM", 100L);
            ScopeKey parentOrg = new ScopeKey("ORGANIZATION", 10L);
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false, Map.of(),
                            Map.of(teamScope, 10L), Set.of(parentOrg), Set.of()));
            when(membershipBatchQueryService.snapshotForUser(eq(6L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false, Map.of(),
                            Map.of(teamScope, 10L), Set.of(), Set.of()));

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE は templateEvaluator に委譲")
        void customTemplate() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.CUSTOM_TEMPLATE);
            p.visibilityTemplateId = 555L;
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(templateEvaluator.canView(eq(5L), eq(555L), eq(999L))).thenReturn(true);
            when(templateEvaluator.canView(eq(6L), eq(555L), eq(999L))).thenReturn(false);

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE で templateId が null なら fail-closed")
        void customTemplate_nullId_failClosed() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.CUSTOM_TEMPLATE);
            p.visibilityTemplateId = null;
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
            verifyNoInteractions(templateEvaluator);
        }

        @Test
        @DisplayName("FOLLOWERS_ONLY は followBatchService に委譲")
        void followersOnly() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.FOLLOWERS_ONLY);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(followBatchService.isFollower(5L, 999L)).thenReturn(true);
            when(followBatchService.isFollower(6L, 999L)).thenReturn(false);

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            assertThat(resolver.filterAccessible(List.of(1L), 6L)).isEmpty();
        }

        @Test
        @DisplayName("FOLLOWERS_ONLY で followBatchService が null なら fail-closed")
        void followersOnly_noService_failClosed() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.FOLLOWERS_ONLY);
            resolver.projections = List.of(p);
            FakeResolver noFollow = new FakeResolver(
                    membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                    null, auditLogService);
            noFollow.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(noFollow.filterAccessible(List.of(1L), 5L)).isEmpty();
        }

        @Test
        @DisplayName("CUSTOM は evaluateCustom に委譲し、recordCustomDispatch が呼ばれる")
        void custom_evaluateCustom() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.CUSTOM);
            resolver.projections = List.of(p);
            resolver.customResult = true;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
            // recordCustomDispatch は内部メトリクスとして Counter が +1 されている
            assertThat(meterRegistry.get("content_visibility.custom_dispatch_count")
                    .tag("referenceType", REF_TYPE.name())
                    .tag("customSubType", "CUSTOM")
                    .counter().count()).isGreaterThanOrEqualTo(1.0);
        }
    }

    // ========================================================================
    // 親 ORG 連鎖ガード（§11.6）
    // ========================================================================

    @Nested
    @DisplayName("親 ORG 連鎖ガード（§11.6）")
    class ParentOrgChain {

        @Test
        @DisplayName("親 ORG が SUSPENDED/DELETED の TEAM コンテンツは SystemAdmin 以外不可視")
        void inactiveParentOrg_blocked() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            ScopeKey teamScope = new ScopeKey("TEAM", 100L);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false, Map.of(),
                            Map.of(teamScope, 10L), Set.of(), Set.of(10L)));

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).isEmpty();
        }

        @Test
        @DisplayName("親 ORG が SUSPENDED でも SystemAdmin は可視")
        void inactiveParentOrg_sysadmin_allowed() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.filterAccessible(List.of(1L), 5L)).containsExactly(1L);
        }
    }

    // ========================================================================
    // decide() — VisibilityDecision の組み立て
    // ========================================================================

    @Nested
    @DisplayName("decide() — VisibilityDecision 組み立て")
    class DecideBehavior {

        @Test
        @DisplayName("contentId=null は NOT_FOUND を返す")
        void decide_nullId() {
            VisibilityDecision d = resolver.decide(null, 1L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
        }

        @Test
        @DisplayName("実存しない ID は NOT_FOUND を返す")
        void decide_notFound() {
            resolver.projections = List.of();
            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
        }

        @Test
        @DisplayName("PRIVATE で他人が見たら NOT_OWNER + resolvedLevel=PRIVATE")
        void decide_private_denied_with_level() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.NOT_OWNER);
            assertThat(d.resolvedLevel()).isEqualTo(StandardVisibility.PRIVATE);
        }

        @Test
        @DisplayName("MEMBERS_ONLY で非所属は NOT_A_MEMBER")
        void decide_membersOnly_notMember() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.MEMBERS_ONLY);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.NOT_A_MEMBER);
        }

        @Test
        @DisplayName("ADMINS_ONLY で SUPPORTER は INSUFFICIENT_ROLE")
        void decide_adminsOnly_insufficientRole() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.ADMINS_ONLY);
            resolver.projections = List.of(p);
            ScopeKey scope = new ScopeKey("TEAM", 100L);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(scope, "SUPPORTER"), Map.of(), Set.of(), Set.of()));

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.INSUFFICIENT_ROLE);
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE rule 不一致は TEMPLATE_RULE_NO_MATCH")
        void decide_customTemplate_noMatch() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.CUSTOM_TEMPLATE);
            p.visibilityTemplateId = 555L;
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(templateEvaluator.canView(any(), eq(555L), eq(999L))).thenReturn(false);

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.TEMPLATE_RULE_NO_MATCH);
            assertThat(d.resolvedLevel()).isEqualTo(StandardVisibility.CUSTOM_TEMPLATE);
        }

        @Test
        @DisplayName("status=DELETED は NOT_FOUND を返す")
        void decide_deleted_notFound() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            resolver.statusOverride = ContentStatus.DELETED;
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
            assertThat(d.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
        }

        @Test
        @DisplayName("allow 時は denyReason=null かつ resolvedLevel が埋まる")
        void decide_publicAllow() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision d = resolver.decide(1L, 5L);
            assertThat(d.allowed()).isTrue();
            assertThat(d.denyReason()).isNull();
            assertThat(d.resolvedLevel()).isEqualTo(StandardVisibility.PUBLIC);
        }
    }

    // ========================================================================
    // 監査ログ連携（§11.4 / マスター裁可 C-1）
    // ========================================================================

    @Nested
    @DisplayName("監査ログ連携（§11.4 / マスター裁可 C-1）")
    class AuditLogIntegration {

        @Test
        @DisplayName("PRIVATE の deny は VISIBILITY_DENIED で記録される")
        void privateDeny_recorded() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            resolver.decide(1L, 5L);

            ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(eventType.capture(),
                    eq(5L), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), anyString());
            assertThat(eventType.getValue()).isEqualTo("VISIBILITY_DENIED");
        }

        @Test
        @DisplayName("PRIVATE の allow（作成者本人）は VISIBILITY_GRANTED_SENSITIVE で記録される")
        void privateAllow_recorded() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            resolver.decide(1L, 999L);

            ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(eventType.capture(),
                    eq(999L), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), anyString());
            assertThat(eventType.getValue()).isEqualTo("VISIBILITY_GRANTED_SENSITIVE");
        }

        @Test
        @DisplayName("PUBLIC の allow は監査ログ記録されない（センシティブでない）")
        void publicAllow_notRecorded() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            resolver.decide(1L, 5L);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("MEMBERS_ONLY の deny は記録されない（センシティブでない）")
        void membersOnlyDeny_notRecorded() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.MEMBERS_ONLY);
            resolver.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            resolver.decide(1L, 5L);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("auditLogService が null でも例外は出ない")
        void nullAuditLog_safe() {
            FakeProjection p = projection(1L, "TEAM", 100L, 999L, FakeVisibility.PRIVATE);
            FakeResolver noAudit = new FakeResolver(
                    membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                    followBatchService, null);
            noAudit.projections = List.of(p);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            VisibilityDecision d = noAudit.decide(1L, 5L);
            assertThat(d.allowed()).isFalse();
        }
    }

    // ========================================================================
    // バッチ性能性質（SQL 集計）
    // ========================================================================

    @Nested
    @DisplayName("バッチ性質")
    class BatchQuality {

        @Test
        @DisplayName("複数 ID でも snapshot 構築は 1 回のみ呼ばれる（N+1 防止）")
        void singleSnapshot_for_batch() {
            FakeProjection p1 = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.MEMBERS_ONLY);
            FakeProjection p2 = projection(2L, "TEAM", 100L, 999L, FakeVisibility.PUBLIC);
            FakeProjection p3 = projection(3L, "TEAM", 200L, 999L,
                    FakeVisibility.ORGANIZATION_WIDE);
            resolver.projections = List.of(p1, p2, p3);
            ScopeKey team1 = new ScopeKey("TEAM", 100L);
            ScopeKey team2 = new ScopeKey("TEAM", 200L);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(team1, "MEMBER"), Map.of(team2, 10L),
                            Set.of(new ScopeKey("ORGANIZATION", 10L)), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L, 3L), 5L);
            assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);

            verify(membershipBatchQueryService, never())
                    .snapshotForUser(eq(5L), anySet(), eq(Set.of()));
            // 呼ばれた回数は 1 回のみ
            verify(membershipBatchQueryService).snapshotForUser(eq(5L), anySet(), anySet());
        }

        @Test
        @DisplayName("ORGANIZATION_WIDE の row のみ orgWideScopes に集約される")
        void orgWideScopes_filtered() {
            FakeProjection orgWide = projection(1L, "TEAM", 100L, 999L,
                    FakeVisibility.ORGANIZATION_WIDE);
            FakeProjection notOrgWide = projection(2L, "TEAM", 200L, 999L,
                    FakeVisibility.MEMBERS_ONLY);
            resolver.projections = List.of(orgWide, notOrgWide);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            resolver.filterAccessible(List.of(1L, 2L), 5L);

            ArgumentCaptor<Set<ScopeKey>> directCaptor = ArgumentCaptor.forClass(Set.class);
            ArgumentCaptor<Set<ScopeKey>> orgWideCaptor = ArgumentCaptor.forClass(Set.class);
            verify(membershipBatchQueryService).snapshotForUser(
                    eq(5L), directCaptor.capture(), orgWideCaptor.capture());
            assertThat(directCaptor.getValue()).containsExactlyInAnyOrder(
                    new ScopeKey("TEAM", 100L), new ScopeKey("TEAM", 200L));
            assertThat(orgWideCaptor.getValue()).containsExactly(
                    new ScopeKey("TEAM", 100L));
        }
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private static FakeProjection projection(Long id, String scopeType, Long scopeId,
                                             Long authorUserId, FakeVisibility visibility) {
        FakeProjection p = new FakeProjection();
        p.id = id;
        p.scopeType = scopeType;
        p.scopeId = scopeId;
        p.authorUserId = authorUserId;
        p.visibility = visibility;
        return p;
    }

    /** テスト用の最小 enum（StandardVisibility の各値に直接マップする）。 */
    enum FakeVisibility {
        PUBLIC, MEMBERS_ONLY, SUPPORTERS_AND_ABOVE, ADMINS_ONLY, PRIVATE,
        FOLLOWERS_ONLY, CUSTOM_TEMPLATE, ORGANIZATION_WIDE, CUSTOM
    }

    /** テスト用の Projection 実装（フィールドアクセスで簡潔に組み立てる）。 */
    static class FakeProjection implements VisibilityProjection {
        Long id;
        String scopeType;
        Long scopeId;
        Long authorUserId;
        Long visibilityTemplateId;
        FakeVisibility visibility;

        @Override public Long id() { return id; }
        @Override public String scopeType() { return scopeType; }
        @Override public Long scopeId() { return scopeId; }
        @Override public Long authorUserId() { return authorUserId; }
        @Override public Long visibilityTemplateId() { return visibilityTemplateId; }
        @Override public Object visibility() { return visibility; }
    }

    /** テスト用のサブクラス。フィールドで挙動を差し替える。 */
    static class FakeResolver
            extends AbstractContentVisibilityResolver<FakeVisibility, FakeProjection> {

        List<FakeProjection> projections = List.of();
        ContentStatus statusOverride = ContentStatus.PUBLISHED;
        boolean customResult = false;

        FakeResolver(MembershipBatchQueryService membershipBatchQueryService,
                     VisibilityTemplateEvaluator templateEvaluator,
                     VisibilityMetrics visibilityMetrics,
                     FollowBatchService followBatchService,
                     AuditLogService auditLogService) {
            super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                    followBatchService, auditLogService);
        }

        @Override
        public ReferenceType referenceType() {
            return REF_TYPE;
        }

        @Override
        protected List<FakeProjection> loadProjections(Collection<Long> ids) {
            return projections;
        }

        @Override
        protected StandardVisibility toStandard(FakeVisibility visibility) {
            return switch (visibility) {
                case PUBLIC -> StandardVisibility.PUBLIC;
                case MEMBERS_ONLY -> StandardVisibility.MEMBERS_ONLY;
                case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
                case ADMINS_ONLY -> StandardVisibility.ADMINS_ONLY;
                case PRIVATE -> StandardVisibility.PRIVATE;
                case FOLLOWERS_ONLY -> StandardVisibility.FOLLOWERS_ONLY;
                case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
                case ORGANIZATION_WIDE -> StandardVisibility.ORGANIZATION_WIDE;
                case CUSTOM -> StandardVisibility.CUSTOM;
            };
        }

        @Override
        protected ContentStatus toContentStatus(FakeProjection row) {
            return statusOverride;
        }

        @Override
        protected boolean evaluateCustom(FakeProjection row, Long viewerUserId,
                                         UserScopeRoleSnapshot snapshot) {
            return customResult;
        }
    }
}
