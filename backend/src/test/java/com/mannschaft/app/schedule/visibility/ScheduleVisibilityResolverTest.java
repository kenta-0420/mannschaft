package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityDecision;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduleVisibilityResolver} の単体テスト（Mockito 構成）。
 *
 * <p>F00 Phase B — SCHEDULE 用 Resolver の判定パイプラインをサブクラス特化観点で検証する：</p>
 * <ul>
 *   <li>{@link ReferenceType#SCHEDULE} 固定登録</li>
 *   <li>{@link ScheduleVisibilityMapper} 経由の {@link StandardVisibility} 正規化</li>
 *   <li>PERSONAL スコープを {@link com.mannschaft.app.common.visibility.ContentStatus#DRAFT}
 *       に正規化し作成者本人のみ可視とする設計判断</li>
 *   <li>TEAM × MEMBERS_ONLY、ORG × ORGANIZATION_WIDE、CUSTOM_TEMPLATE 委譲、SystemAdmin 高速パス</li>
 *   <li>NOT_FOUND（射影が空）／ DELETED 相当（status null）</li>
 * </ul>
 *
 * <p>判定パイプライン本体は {@code AbstractContentVisibilityResolverTest} で網羅検証済みのため、
 * 本テストは Schedule 固有の挙動（PERSONAL → DRAFT、status マッピング、Mapper 連携）に集中する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleVisibilityResolver — SCHEDULE 用 Resolver")
class ScheduleVisibilityResolverTest {

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;
    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;
    @Mock
    private FollowBatchService followBatchService;
    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private ScheduleVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new ScheduleVisibilityResolver(
                scheduleRepository,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService);
    }

    @Test
    @DisplayName("referenceType() は SCHEDULE を返す")
    void referenceType_isSchedule() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.SCHEDULE);
    }

    // ========================================================================
    // PERSONAL スコープ — 作成者本人のみ可視（DRAFT 正規化）
    // ========================================================================

    @Nested
    @DisplayName("PERSONAL スコープ（作成者本人のみ可視）")
    class PersonalScope {

        @Test
        @DisplayName("作成者本人は閲覧可")
        void personal_owner_canView() {
            ScheduleVisibilityProjection row = personal(1L, /*createdBy*/ 100L);
            stubProjection(row);
            stubSnapshotEmpty(100L);

            assertThat(resolver.canView(1L, 100L)).isTrue();
        }

        @Test
        @DisplayName("他人は閲覧不可")
        void personal_other_denied() {
            ScheduleVisibilityProjection row = personal(1L, /*createdBy*/ 100L);
            stubProjection(row);
            stubSnapshotEmpty(200L);

            assertThat(resolver.canView(1L, 200L)).isFalse();
        }

        @Test
        @DisplayName("created_by が null でも user_id が作成者にフォールバックされる")
        void personal_createdByFallback() {
            ScheduleVisibilityProjection row = new ScheduleVisibilityProjection(
                    1L, null, null, /*userId*/ 100L,
                    /*createdBy*/ null,
                    ScheduleVisibility.MEMBERS_ONLY, null,
                    ScheduleStatus.SCHEDULED);
            stubProjection(row);
            stubSnapshotEmpty(100L);

            assertThat(resolver.canView(1L, 100L)).isTrue();
        }

        @Test
        @DisplayName("未認証ユーザー（userId=null）は閲覧不可")
        void personal_anonymous_denied() {
            ScheduleVisibilityProjection row = personal(1L, /*createdBy*/ 100L);
            stubProjection(row);
            when(membershipBatchQueryService.snapshotForUser(eq(null), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は他人の PERSONAL でも閲覧可")
        void personal_systemAdmin_canView() {
            ScheduleVisibilityProjection row = personal(1L, /*createdBy*/ 100L);
            stubProjection(row);
            when(membershipBatchQueryService.snapshotForUser(eq(999L), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 999L)).isTrue();
        }
    }

    // ========================================================================
    // TEAM スコープ × MEMBERS_ONLY
    // ========================================================================

    @Nested
    @DisplayName("TEAM スコープ × MEMBERS_ONLY")
    class TeamMembersOnly {

        @Test
        @DisplayName("チームメンバーは閲覧可")
        void member_canView() {
            ScheduleVisibilityProjection row = team(1L, 10L, ScheduleVisibility.MEMBERS_ONLY);
            stubProjection(row);
            ScopeKey scope = new ScopeKey("TEAM", 10L);
            when(membershipBatchQueryService.snapshotForUser(eq(200L), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(scope, "MEMBER"),
                            Map.of(),
                            Set.of(),
                            Set.of()));

            assertThat(resolver.canView(1L, 200L)).isTrue();
        }

        @Test
        @DisplayName("非メンバーは閲覧不可")
        void nonMember_denied() {
            ScheduleVisibilityProjection row = team(1L, 10L, ScheduleVisibility.MEMBERS_ONLY);
            stubProjection(row);
            stubSnapshotEmpty(200L);

            assertThat(resolver.canView(1L, 200L)).isFalse();
        }
    }

    // ========================================================================
    // ORGANIZATION スコープ × ORGANIZATION（→ ORGANIZATION_WIDE）
    // ========================================================================

    @Nested
    @DisplayName("ORGANIZATION スコープ × ORGANIZATION（→ ORGANIZATION_WIDE）")
    class OrgOrganizationWide {

        @Test
        @DisplayName("親 ORG メンバーは閲覧可")
        void orgMember_canView() {
            ScheduleVisibilityProjection row = org(1L, 20L, ScheduleVisibility.ORGANIZATION);
            stubProjection(row);
            ScopeKey orgScope = new ScopeKey("ORGANIZATION", 20L);
            when(membershipBatchQueryService.snapshotForUser(eq(300L), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(),
                            Map.of(orgScope, 20L),
                            Set.of(orgScope),
                            Set.of()));

            assertThat(resolver.canView(1L, 300L)).isTrue();
        }

        @Test
        @DisplayName("親 ORG 非アクティブなら fail-closed")
        void parentOrgInactive_denied() {
            ScheduleVisibilityProjection row = org(1L, 20L, ScheduleVisibility.ORGANIZATION);
            stubProjection(row);
            ScopeKey orgScope = new ScopeKey("ORGANIZATION", 20L);
            when(membershipBatchQueryService.snapshotForUser(eq(300L), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(),
                            Map.of(orgScope, 20L),
                            Set.of(orgScope),
                            Set.of(20L)));

            assertThat(resolver.canView(1L, 300L)).isFalse();
        }
    }

    // ========================================================================
    // CUSTOM_TEMPLATE 委譲
    // ========================================================================

    @Nested
    @DisplayName("CUSTOM_TEMPLATE — VisibilityTemplateEvaluator へ委譲")
    class CustomTemplate {

        @Test
        @DisplayName("テンプレート評価が true なら可視")
        void template_allow() {
            ScheduleVisibilityProjection row = new ScheduleVisibilityProjection(
                    1L, 10L, null, null, 100L,
                    ScheduleVisibility.CUSTOM_TEMPLATE, /*templateId*/ 555L,
                    ScheduleStatus.SCHEDULED);
            stubProjection(row);
            stubSnapshotEmpty(200L);
            when(templateEvaluator.canView(200L, 555L, 100L)).thenReturn(true);

            assertThat(resolver.canView(1L, 200L)).isTrue();
        }

        @Test
        @DisplayName("テンプレート評価が false なら不可視")
        void template_deny() {
            ScheduleVisibilityProjection row = new ScheduleVisibilityProjection(
                    1L, 10L, null, null, 100L,
                    ScheduleVisibility.CUSTOM_TEMPLATE, 555L,
                    ScheduleStatus.SCHEDULED);
            stubProjection(row);
            stubSnapshotEmpty(200L);
            when(templateEvaluator.canView(200L, 555L, 100L)).thenReturn(false);

            assertThat(resolver.canView(1L, 200L)).isFalse();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE で template_id が null なら fail-closed")
        void template_idNull_denied() {
            ScheduleVisibilityProjection row = new ScheduleVisibilityProjection(
                    1L, 10L, null, null, 100L,
                    ScheduleVisibility.CUSTOM_TEMPLATE, /*templateId*/ null,
                    ScheduleStatus.SCHEDULED);
            stubProjection(row);
            stubSnapshotEmpty(200L);

            assertThat(resolver.canView(1L, 200L)).isFalse();
        }
    }

    // ========================================================================
    // 不存在 / 異常系
    // ========================================================================

    @Nested
    @DisplayName("不存在 / 異常系")
    class NotFoundAndDeleted {

        @Test
        @DisplayName("loadProjections が空なら NOT_FOUND（IDOR 防止）")
        void notFound_returnsFalse() {
            when(scheduleRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of());

            assertThat(resolver.canView(999L, 100L)).isFalse();
            VisibilityDecision decision = resolver.decide(999L, 100L);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("status が null なら DELETED 相当で fail-closed")
        void statusNull_failClosed() {
            ScheduleVisibilityProjection row = new ScheduleVisibilityProjection(
                    1L, 10L, null, null, 100L,
                    ScheduleVisibility.MEMBERS_ONLY, null,
                    /*status*/ null);
            stubProjection(row);
            stubSnapshotEmpty(200L);

            assertThat(resolver.canView(1L, 200L)).isFalse();
        }
    }

    // ========================================================================
    // バッチ判定
    // ========================================================================

    @Test
    @DisplayName("filterAccessible — TEAM/ORG/PERSONAL 混在で正しくフィルタされる")
    void filterAccessible_mixedScopes() {
        ScheduleVisibilityProjection teamRow = team(1L, 10L, ScheduleVisibility.MEMBERS_ONLY);
        ScheduleVisibilityProjection orgRow = org(2L, 20L, ScheduleVisibility.ORGANIZATION);
        ScheduleVisibilityProjection personalOwnerRow = personal(3L, /*createdBy*/ 200L);
        ScheduleVisibilityProjection personalOtherRow = personal(4L, /*createdBy*/ 999L);
        when(scheduleRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(teamRow, orgRow, personalOwnerRow, personalOtherRow));

        ScopeKey teamScope = new ScopeKey("TEAM", 10L);
        ScopeKey orgScope = new ScopeKey("ORGANIZATION", 20L);
        when(membershipBatchQueryService.snapshotForUser(eq(200L), any(), any()))
                .thenReturn(new UserScopeRoleSnapshot(
                        false,
                        Map.of(teamScope, "MEMBER"),
                        Map.of(orgScope, 20L),
                        Set.of(orgScope),
                        Set.of()));

        Set<Long> accessible = resolver.filterAccessible(List.of(1L, 2L, 3L, 4L), 200L);

        // teamRow (1L): メンバー → 可
        // orgRow (2L): 親 ORG メンバー → 可
        // personalOwnerRow (3L): 作成者本人 → 可
        // personalOtherRow (4L): 他人 → 不可
        assertThat(accessible).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private void stubProjection(ScheduleVisibilityProjection row) {
        when(scheduleRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(row));
    }

    private void stubSnapshotEmpty(long viewerId) {
        when(membershipBatchQueryService.snapshotForUser(eq(viewerId), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());
    }

    private static ScheduleVisibilityProjection personal(long id, Long createdBy) {
        return new ScheduleVisibilityProjection(
                id, null, null, /*userId*/ createdBy,
                createdBy,
                ScheduleVisibility.MEMBERS_ONLY, null,
                ScheduleStatus.SCHEDULED);
    }

    private static ScheduleVisibilityProjection team(long id, Long teamId, ScheduleVisibility v) {
        return new ScheduleVisibilityProjection(
                id, teamId, null, null, 100L,
                v, null, ScheduleStatus.SCHEDULED);
    }

    private static ScheduleVisibilityProjection org(long id, Long orgId, ScheduleVisibility v) {
        return new ScheduleVisibilityProjection(
                id, null, orgId, null, 100L,
                v, null, ScheduleStatus.SCHEDULED);
    }
}
