package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase C — {@link CirculationDocumentVisibilityResolver} 単体テスト。
 *
 * <p>{@link CirculationDocumentRepository} / {@link CirculationRecipientRepository} /
 * {@link MembershipBatchQueryService} をモック化し、案 A の ACL 判定が網羅的に動くことを検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * 回覧板固有の振る舞い（recipients ACL / status 写像）を重点的に確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationDocumentVisibilityResolver — 単体テスト")
class CirculationDocumentVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    private VisibilityMetrics visibilityMetrics;
    private CirculationDocumentVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new CirculationDocumentVisibilityResolver(
                documentRepository,
                recipientRepository,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,            // FollowBatchService 不要
                auditLogService);
    }

    @Test
    @DisplayName("referenceType() は CIRCULATION_DOCUMENT を返す")
    void referenceType_is_CIRCULATION_DOCUMENT() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.CIRCULATION_DOCUMENT);
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
            verifyNoInteractions(documentRepository);
            verifyNoInteractions(recipientRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(documentRepository);
            verifyNoInteractions(recipientRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false（IDOR 防止）")
        void canView_unknownId_false() {
            when(documentRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // ACL 判定（案 A）
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("recipients ACL 判定")
    class RecipientAcl {

        @Test
        @DisplayName("recipients に登録済の閲覧者は ACTIVE 文書を閲覧可")
        void recipient_can_view_active() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(recipientRepository.existsByDocumentIdAndUserId(1L, 5L)).thenReturn(true);

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("recipients に未登録の閲覧者は ACTIVE 文書を閲覧不可")
        void non_recipient_cannot_view_active() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(recipientRepository.existsByDocumentIdAndUserId(1L, 5L)).thenReturn(false);

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("作成者本人は recipients 未登録でも ACTIVE 文書を閲覧可")
        void author_can_view_without_recipient_record() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 5L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
            // 作成者本人ショートサーキットにより recipients 照会は走らない
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("未認証ユーザー（viewerUserId=null）は閲覧不可（fail-closed）")
        void anonymous_cannot_view() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("SystemAdmin は recipients 未登録でも閲覧可（基底クラス §15 D-13）")
        void system_admin_can_view_without_recipient_record() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 99_999L)).isTrue();
            // SystemAdmin 高速パスにより recipients 照会は走らない
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("recipients 登録者は COMPLETED 文書も閲覧可")
        void recipient_can_view_completed() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.COMPLETED);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(recipientRepository.existsByDocumentIdAndUserId(1L, 5L)).thenReturn(true);

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
        @DisplayName("DRAFT は作成者本人のみ閲覧可（recipients 登録の有無に関係なく）")
        void draft_visible_only_to_author() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 5L,
                    CirculationStatus.DRAFT);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は recipients 登録済の閲覧者にも不可視（status 軸で先に弾かれる）")
        void draft_invisible_to_recipient_other_than_author() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.DRAFT);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
            // status 軸で fail-closed されるため recipients 照会は走らない
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin のみ閲覧可（ARCHIVED 扱い）")
        void cancelled_visible_only_to_system_admin() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.CANCELLED);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            // 作成者本人ですら CANCELLED は閲覧不可
            assertThat(resolver.canView(1L, 99L)).isFalse();
            verify(recipientRepository, never()).existsByDocumentIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("CANCELLED は SystemAdmin に可視")
        void cancelled_visible_to_system_admin() {
            CirculationDocumentVisibilityProjection p = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.CANCELLED);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
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
        void single_repo_call_for_batch() {
            CirculationDocumentVisibilityProjection p1 = projection(1L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            CirculationDocumentVisibilityProjection p2 = projection(2L, "TEAM", 100L, 99L,
                    CirculationStatus.ACTIVE);
            when(documentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));
            when(recipientRepository.existsByDocumentIdAndUserId(1L, 5L)).thenReturn(true);
            when(recipientRepository.existsByDocumentIdAndUserId(2L, 5L)).thenReturn(false);

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 5L);

            assertThat(result).containsExactly(1L);
            verify(documentRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static CirculationDocumentVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId, CirculationStatus status) {
        return new CirculationDocumentVisibilityProjection(
                id, scopeType, scopeId, authorUserId, status);
    }
}
