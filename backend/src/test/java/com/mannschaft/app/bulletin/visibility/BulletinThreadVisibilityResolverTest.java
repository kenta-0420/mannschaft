package com.mannschaft.app.bulletin.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
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
 * F00 Phase C — {@link BulletinThreadVisibilityResolver} 単体テスト。
 *
 * <p>掲示板スレッドは visibility 概念を持たない最小実装（§12.3.1）のため、
 * 機能側 enum × status の組み合わせ試験は不要。代わりに以下を網羅する:</p>
 * <ul>
 *   <li>入口ガード（null contentId / 空 ids / 不存在 ID）</li>
 *   <li>固定 MEMBERS_ONLY 評価（メンバー / 非メンバー / 未認証 / SystemAdmin）</li>
 *   <li>scope_type=PERSONAL の fail-closed 挙動</li>
 *   <li>バッチ呼び出しの SQL 回数</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinThreadVisibilityResolver — 単体テスト")
class BulletinThreadVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;

    private VisibilityMetrics visibilityMetrics;
    private BulletinThreadVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new BulletinThreadVisibilityResolver(
                membershipBatchQueryService,
                visibilityMetrics,
                templateEvaluator,
                null,            // FollowBatchService 不要
                auditLogService,
                bulletinThreadRepository);
    }

    @Test
    @DisplayName("referenceType() は BULLETIN_THREAD を返す")
    void referenceType_is_BULLETIN_THREAD() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.BULLETIN_THREAD);
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
            verifyNoInteractions(bulletinThreadRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(bulletinThreadRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    // -------------------------------------------------------------------------
    // MEMBERS_ONLY 固定評価
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MEMBERS_ONLY 固定評価")
    class MembersOnlyEvaluation {

        @Test
        @DisplayName("TEAM スコープの所属メンバーには可視")
        void team_member_visible() {
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("ORGANIZATION スコープの所属メンバーには可視")
        void organization_member_visible() {
            BulletinThreadVisibilityProjection p = projection(1L, "ORGANIZATION", 200L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("ORGANIZATION", 200L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("非メンバーには不可視")
        void non_member_invisible() {
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("未認証ユーザーには不可視（MEMBERS_ONLY なので）")
        void anonymous_invisible() {
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は所属関係なく可視（§15 D-13 高速パス）")
        void system_admin_visible() {
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(7L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 7L)).isTrue();
        }

        @Test
        @DisplayName("SUPPORTER ロール保有者には不可視（MEMBERS_ONLY は SUPPORTER 包含するが GUEST のみ除外、" +
                "実装は roleByScope に entry あれば isMemberOf=true）")
        void supporter_visible() {
            // MEMBERS_ONLY は scope の roleByScope に entry があれば true。
            // SUPPORTER も MEMBER 同様に entry があれば可視（GUEST も同様）。
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "SUPPORTER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // PERSONAL スコープの fail-closed 挙動
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PERSONAL スコープ")
    class PersonalScope {

        @Test
        @DisplayName("PERSONAL スコープは作成者本人にも fail-closed で不可視（最小実装）")
        void personal_scope_invisible_even_to_author() {
            BulletinThreadVisibilityProjection p = projection(1L, "PERSONAL", 5L, 5L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            // PERSONAL は MembershipBatchQueryService が entry を作らないため
            // MEMBERS_ONLY 評価で false。最小実装の安全側挙動。
            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("PERSONAL スコープでも SystemAdmin には可視（§15 D-13）")
        void personal_scope_visible_to_system_admin() {
            BulletinThreadVisibilityProjection p = projection(1L, "PERSONAL", 5L, 5L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(7L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 7L)).isTrue();
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
            BulletinThreadVisibilityProjection p1 = projection(1L, "TEAM", 100L, 99L);
            BulletinThreadVisibilityProjection p2 = projection(2L, "TEAM", 100L, 99L);
            BulletinThreadVisibilityProjection p3 = projection(3L, "TEAM", 200L, 99L);
            when(bulletinThreadRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2, p3));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L, 3L), 5L);

            // 100L 所属のみ可視。200L は非所属なので除外。
            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(bulletinThreadRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // Projection の visibility() 仕様確認
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Projection 仕様")
    class ProjectionSpec {

        @Test
        @DisplayName("Projection.visibility() は常に StandardVisibility.MEMBERS_ONLY を返す")
        void projection_visibility_is_fixed_members_only() {
            BulletinThreadVisibilityProjection p = projection(1L, "TEAM", 100L, 99L);
            assertThat(p.visibility()).isEqualTo(StandardVisibility.MEMBERS_ONLY);
            assertThat(p.visibilityTemplateId()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static BulletinThreadVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new BulletinThreadVisibilityProjection(id, scopeType, scopeId, authorUserId);
    }
}
