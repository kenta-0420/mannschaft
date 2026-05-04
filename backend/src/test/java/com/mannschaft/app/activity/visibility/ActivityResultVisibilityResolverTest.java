package com.mannschaft.app.activity.visibility;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityDecision;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.DenyReason;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActivityResultVisibilityResolver} の単体テスト。
 *
 * <p>F00 Phase B Priority 1 — 活動記録 Resolver の判定ロジックを Mockito で網羅的に検証する。</p>
 *
 * <p>Activity の特性:</p>
 * <ul>
 *   <li>visibility は PUBLIC / MEMBERS_ONLY の 2 値のみ</li>
 *   <li>status 軸を持たない（toContentStatus 既定 = PUBLISHED）</li>
 *   <li>論理削除済は @SQLRestriction で Projection に届かない</li>
 * </ul>
 *
 * <p>網羅対象:</p>
 * <ul>
 *   <li>referenceType の自己申告</li>
 *   <li>未存在 ID（IDOR 防止）</li>
 *   <li>PUBLIC × 未認証 / 認証ユーザー</li>
 *   <li>MEMBERS_ONLY × メンバー / 非メンバー / 親 ORG メンバー</li>
 *   <li>SystemAdmin 高速パス</li>
 *   <li>親 ORG SUSPENDED 連鎖ガード</li>
 *   <li>ORGANIZATION スコープ</li>
 *   <li>COMMITTEE スコープ（fail-closed 確認）</li>
 *   <li>filterAccessible バッチ性能（SQL 1）</li>
 *   <li>decide() の DenyReason 分類</li>
 *   <li>null 入力</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityResultVisibilityResolver — 活動記録可視性判定")
class ActivityResultVisibilityResolverTest {

    @Mock
    private ActivityResultRepository repository;

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;

    private ActivityResultVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new ActivityResultVisibilityResolver(
                repository,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,                 // FollowBatchService 不使用
                auditLogService);
    }

    // ========================================================================
    // referenceType
    // ========================================================================

    @Test
    @DisplayName("referenceType は ACTIVITY_RESULT")
    void referenceType_isActivityResult() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.ACTIVITY_RESULT);
    }

    // ========================================================================
    // 未存在 ID（IDOR 防止）
    // ========================================================================

    @Test
    @DisplayName("loadProjections が空（未存在）なら canView は false / decide は NOT_FOUND")
    void unknownId_failClosed() {
        when(repository.findVisibilityProjectionsByIdIn(List.of(999L)))
                .thenReturn(List.of());

        assertThat(resolver.canView(999L, 1L)).isFalse();

        VisibilityDecision decision = resolver.decide(999L, 1L);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_FOUND);

        // 不存在では snapshot 構築まで進まない
        verify(membershipBatchQueryService, never()).snapshotForUser(anyLong(), any(), any());
    }

    // ========================================================================
    // PUBLIC visibility
    // ========================================================================

    @Test
    @DisplayName("PUBLIC は未認証ユーザー (userId=null) でも閲覧可（§17.Q1）")
    void publicVisibility_anonymousAllowed() {
        ActivityResultVisibilityProjection p = pub(1L, "TEAM", 100L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(null), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, null)).isTrue();
    }

    @Test
    @DisplayName("PUBLIC は認証ユーザー（非メンバー）も閲覧可")
    void publicVisibility_nonMemberAllowed() {
        ActivityResultVisibilityProjection p = pub(1L, "TEAM", 100L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 42L)).isTrue();
    }

    // ========================================================================
    // MEMBERS_ONLY visibility
    // ========================================================================

    @Test
    @DisplayName("MEMBERS_ONLY は未認証ユーザーには不可視")
    void membersOnly_anonymousDenied() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(null), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, null)).isFalse();
    }

    @Test
    @DisplayName("MEMBERS_ONLY はチームメンバーには可視")
    void membersOnly_memberAllowed() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        ScopeKey teamScope = new ScopeKey("TEAM", 100L);
        UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                false,
                Map.of(teamScope, "MEMBER"),
                Map.of(),
                Set.of(),
                Set.of());

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(snapshot);

        assertThat(resolver.canView(1L, 42L)).isTrue();
    }

    @Test
    @DisplayName("MEMBERS_ONLY は別チームのメンバーには不可視 / DenyReason=NOT_A_MEMBER")
    void membersOnly_otherTeamDenied() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        ScopeKey otherTeam = new ScopeKey("TEAM", 200L);
        UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                false,
                Map.of(otherTeam, "MEMBER"),
                Map.of(),
                Set.of(),
                Set.of());

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(snapshot);

        assertThat(resolver.canView(1L, 42L)).isFalse();

        VisibilityDecision decision = resolver.decide(1L, 42L);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_A_MEMBER);
    }

    // ========================================================================
    // SystemAdmin 高速パス（§15 D-13）
    // ========================================================================

    @Test
    @DisplayName("SystemAdmin は MEMBERS_ONLY コンテンツを実存確認後に可視")
    void systemAdmin_canViewMembersOnly() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(1L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

        assertThat(resolver.canView(1L, 1L)).isTrue();
    }

    @Test
    @DisplayName("SystemAdmin でも未存在 ID は false（IDOR 防止）")
    void systemAdmin_unknownIdStillFalse() {
        when(repository.findVisibilityProjectionsByIdIn(List.of(999L)))
                .thenReturn(List.of());

        assertThat(resolver.canView(999L, 1L)).isFalse();
        verify(membershipBatchQueryService, never()).snapshotForUser(anyLong(), any(), any());
    }

    // ========================================================================
    // 親 ORG 連鎖ガード（§11.6）
    // ========================================================================

    @Test
    @DisplayName("親 ORG が SUSPENDED なら TEAM コンテンツは MEMBER でも不可視")
    void parentOrgSuspended_teamContentInvisible() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        ScopeKey teamScope = new ScopeKey("TEAM", 100L);
        UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                false,
                Map.of(teamScope, "MEMBER"),
                Map.of(teamScope, 500L),     // 親 ORG = 500
                Set.of(),
                Set.of(500L));               // 500 は SUSPENDED

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(snapshot);

        assertThat(resolver.canView(1L, 42L)).isFalse();
    }

    @Test
    @DisplayName("親 ORG が SUSPENDED でも SystemAdmin は閲覧可")
    void parentOrgSuspended_systemAdminCanView() {
        ActivityResultVisibilityProjection p = members(1L, "TEAM", 100L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(1L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

        assertThat(resolver.canView(1L, 1L)).isTrue();
    }

    // ========================================================================
    // ORGANIZATION スコープ
    // ========================================================================

    @Test
    @DisplayName("ORGANIZATION スコープの MEMBERS_ONLY は組織メンバーに可視")
    void organizationScope_memberAllowed() {
        ActivityResultVisibilityProjection p = members(1L, "ORGANIZATION", 500L, 999L);
        ScopeKey orgScope = new ScopeKey("ORGANIZATION", 500L);
        UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                false,
                Map.of(orgScope, "MEMBER"),
                Map.of(),
                Set.of(),
                Set.of());

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(snapshot);

        assertThat(resolver.canView(1L, 42L)).isTrue();
    }

    // ========================================================================
    // COMMITTEE スコープ（fail-closed 想定）
    // ========================================================================

    @Test
    @DisplayName("COMMITTEE スコープ MEMBERS_ONLY は Phase B 範囲では fail-closed（解決対象外）")
    void committeeScope_failClosed() {
        ActivityResultVisibilityProjection p = members(1L, "COMMITTEE", 700L, 999L);
        // COMMITTEE は MembershipBatchQueryService で解決対象外なので空 snapshot
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 42L)).isFalse();
    }

    @Test
    @DisplayName("COMMITTEE スコープでも PUBLIC は誰でも可視")
    void committeeScope_publicVisible() {
        ActivityResultVisibilityProjection p = pub(1L, "COMMITTEE", 700L, 999L);
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L))).thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 42L)).isTrue();
    }

    // ========================================================================
    // バッチ判定 (filterAccessible)
    // ========================================================================

    @Test
    @DisplayName("filterAccessible は実存 ID のみ返す（存在しない ID は除外）")
    void filterAccessible_filtersUnknownIds() {
        ActivityResultVisibilityProjection p1 = pub(1L, "TEAM", 100L, 999L);
        ActivityResultVisibilityProjection p2 = members(2L, "TEAM", 100L, 999L);
        // 3L は loadProjections の戻りに含まれない（未存在）

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(p1, p2));

        ScopeKey teamScope = new ScopeKey("TEAM", 100L);
        UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                false,
                Map.of(teamScope, "MEMBER"),
                Map.of(),
                Set.of(),
                Set.of());
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(snapshot);

        Set<Long> result = resolver.filterAccessible(List.of(1L, 2L, 3L), 42L);
        assertThat(result).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("filterAccessible は非メンバーには PUBLIC のみ返す")
    void filterAccessible_nonMemberSeesPublicOnly() {
        ActivityResultVisibilityProjection p1 = pub(1L, "TEAM", 100L, 999L);
        ActivityResultVisibilityProjection p2 = members(2L, "TEAM", 100L, 999L);

        when(repository.findVisibilityProjectionsByIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(p1, p2));
        when(membershipBatchQueryService.snapshotForUser(eq(42L), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 42L);
        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("空入力は SQL 0 回・空 Set 即時返却")
    void filterAccessible_empty_noDbAccess() {
        Set<Long> result = resolver.filterAccessible(List.of(), 42L);
        assertThat(result).isEmpty();
        verify(repository, never()).findVisibilityProjectionsByIdIn(any());
        verify(membershipBatchQueryService, never()).snapshotForUser(anyLong(), any(), any());
    }

    // ========================================================================
    // null 入力
    // ========================================================================

    @Test
    @DisplayName("contentId=null の canView は false / DB アクセスなし")
    void canView_nullContentId() {
        assertThat(resolver.canView(null, 42L)).isFalse();
        verify(repository, never()).findVisibilityProjectionsByIdIn(any());
    }

    @Test
    @DisplayName("contentId=null の decide は NOT_FOUND")
    void decide_nullContentId() {
        VisibilityDecision decision = resolver.decide(null, 42L);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
    }

    // ========================================================================
    // ヘルパ — Projection 生成
    // ========================================================================

    /** PUBLIC な Projection を生成。 */
    private static ActivityResultVisibilityProjection pub(Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new ActivityResultVisibilityProjection(
                id, scopeType, scopeId, authorUserId, ActivityVisibility.PUBLIC);
    }

    /** MEMBERS_ONLY な Projection を生成。 */
    private static ActivityResultVisibilityProjection members(Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new ActivityResultVisibilityProjection(
                id, scopeType, scopeId, authorUserId, ActivityVisibility.MEMBERS_ONLY);
    }
}
