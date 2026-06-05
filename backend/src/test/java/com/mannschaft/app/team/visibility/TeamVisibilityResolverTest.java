package com.mannschaft.app.team.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase D-3 — {@link TeamVisibilityResolver} 単体テスト。
 *
 * <p>{@code @ConditionalOnProperty} を使う {@link TeamVisibilityResolver} は
 * {@code @SpringBootTest} なしの Mockito ユニットテストでは Bean 自動登録されないため、
 * コンストラクタで Mock を直接渡して {@code new} する方式を採用する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * Team 固有の正規化（PUBLIC / ORGANIZATION_ONLY / PRIVATE → StandardVisibility）と
 * status 軸（archivedAt / deletedAt）の変換に焦点を当てる。</p>
 *
 * <p>D-3 マッピング:
 * <ul>
 *   <li>PUBLIC → StandardVisibility.PUBLIC</li>
 *   <li>ORGANIZATION_ONLY → StandardVisibility.ORGANIZATION_WIDE（親 ORG メンバーまで公開）</li>
 *   <li>PRIVATE → StandardVisibility.SCOPE_AFFILIATED（招待制・非公開チームはメンバー閲覧可）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamVisibilityResolver — 単体テスト")
class TeamVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private TeamRepository teamRepository;

    private VisibilityMetrics visibilityMetrics;
    private TeamVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        // @ConditionalOnProperty 非対応のため直接 new する
        resolver = new TeamVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,           // FollowBatchService 不要
                auditLogService,
                teamRepository);
    }

    @Test
    @DisplayName("referenceType() は TEAM を返す")
    void referenceType_is_TEAM() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.TEAM);
    }

    // -------------------------------------------------------------------------
    // PUBLIC チームは全員閲覧可
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("canView_PUBLICチームは全員閲覧可: 非メンバーでも true")
    void canView_PUBLICチームは全員閲覧可() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PUBLIC, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        // 非メンバー（空スナップショット）
        when(membershipBatchQueryService.snapshotForUser(eq(99L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 99L)).isTrue();
    }

    // -------------------------------------------------------------------------
    // ORGANIZATION_ONLY チームは親 ORG メンバーのみ
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("canView_ORGANIZATION_ONLYチームは親ORGメンバーに可視")
    void canView_ORGANIZATION_ONLYチームは親ORGメンバーに可視() {
        // チーム(id=1)の親ORGがorg(id=100)という想定
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.ORGANIZATION_ONLY, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        // 親ORG=100のメンバー
        when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                .thenReturn(new UserScopeRoleSnapshot(false,
                        Map.of(),
                        Map.of(new ScopeKey("TEAM", 1L), 100L),
                        Set.of(new ScopeKey("ORGANIZATION", 100L)),
                        Set.of()));

        assertThat(resolver.canView(1L, 5L)).isTrue();
    }

    @Test
    @DisplayName("canView_ORGANIZATION_ONLYチームは非ORGメンバーに不可視")
    void canView_ORGANIZATION_ONLYチームは非ORGメンバーに不可視() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.ORGANIZATION_ONLY, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        // 親ORG非所属ユーザー
        when(membershipBatchQueryService.snapshotForUser(eq(99L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 99L)).isFalse();
    }

    // -------------------------------------------------------------------------
    // PRIVATE チームは MEMBERS_ONLY にマップ（招待制・非公開チームはメンバー閲覧可）
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("canView_PRIVATEチームはチームメンバーに可視")
    void canView_PRIVATEチームはチームメンバーに可視() {
        // PRIVATE → MEMBERS_ONLY: チームメンバー（memberScopes に TEAM:1 を持つ）は閲覧可
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PRIVATE, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(10L), anySet(), anySet()))
                .thenReturn(new UserScopeRoleSnapshot(false,
                        Map.of(new ScopeKey("TEAM", 1L), "MEMBER"),
                        Map.of(),
                        Set.of(new ScopeKey("TEAM", 1L)),
                        Set.of()));

        assertThat(resolver.canView(1L, 10L)).isTrue();
    }

    @Test
    @DisplayName("canView_PRIVATEチームは非メンバーに不可視")
    void canView_PRIVATEチームは非メンバーに不可視() {
        // PRIVATE → MEMBERS_ONLY: チーム非メンバーは閲覧不可
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PRIVATE, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(99L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 99L)).isFalse();
    }

    @Test
    @DisplayName("canView_PRIVATEチームはSystemAdminに可視（高速パス）")
    void canView_PRIVATEチームはSystemAdminに可視() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PRIVATE, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(1L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

        assertThat(resolver.canView(1L, 1L)).isTrue();
    }

    // -------------------------------------------------------------------------
    // ARCHIVEDはSystemAdminのみ
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("canView_ARCHIVEDはSystemAdminのみ: SystemAdmin は true")
    void canView_ARCHIVEDはSystemAdminに可視() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PUBLIC,
                LocalDateTime.now().minusDays(1), null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(1L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

        assertThat(resolver.canView(1L, 1L)).isTrue();
    }

    @Test
    @DisplayName("canView_ARCHIVEDはSystemAdminのみ: 一般ユーザーは false")
    void canView_ARCHIVEDは一般ユーザーに不可視() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PUBLIC,
                LocalDateTime.now().minusDays(1), null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(99L), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, 99L)).isFalse();
    }

    // -------------------------------------------------------------------------
    // 不存在IDはfalse
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("canView_不存在IDはfalse: リポジトリが空リストを返す場合")
    void canView_不存在IDはfalse() {
        when(teamRepository.findVisibilityProjectionsByIdIn(eq(List.of(999L))))
                .thenReturn(List.of());

        assertThat(resolver.canView(999L, 1L)).isFalse();
        verifyNoInteractions(membershipBatchQueryService);
    }

    @Test
    @DisplayName("contentId=null は false（入口ガード）")
    void canView_nullIdはfalse() {
        assertThat(resolver.canView(null, 1L)).isFalse();
        verifyNoInteractions(teamRepository);
        verifyNoInteractions(membershipBatchQueryService);
    }

    @Test
    @DisplayName("userId=null でも PUBLIC チームは true")
    void canView_nullUserIdでPUBLICはtrue() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.PUBLIC, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(null), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, null)).isTrue();
    }

    @Test
    @DisplayName("userId=null で ORGANIZATION_ONLY チームは false")
    void canView_nullUserIdでORGANIZATION_ONLYはfalse() {
        TeamVisibilityProjection projection = projection(
                1L, 1L, TeamEntity.Visibility.ORGANIZATION_ONLY, null, null);
        when(teamRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(projection));
        when(membershipBatchQueryService.snapshotForUser(eq(null), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty());

        assertThat(resolver.canView(1L, null)).isFalse();
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static TeamVisibilityProjection projection(
            Long id, Long teamId, TeamEntity.Visibility visibility,
            LocalDateTime archivedAt, LocalDateTime deletedAt) {
        return new TeamVisibilityProjection(id, teamId, visibility, archivedAt, deletedAt);
    }
}
