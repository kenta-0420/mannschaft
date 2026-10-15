package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.circulation.repository.CirculationCommentRepository;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.RecursionDepthCounter;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase C — {@link CirculationCommentVisibilityResolver} 単体テスト。
 *
 * <p>{@link CirculationCommentRepository} / {@link ContentVisibilityChecker} /
 * {@link MembershipBatchQueryService} をモック化し、親従属 ACL 判定が網羅的に動くことを検証する。</p>
 *
 * <p>コメントは親文書 ({@link ReferenceType#CIRCULATION_DOCUMENT}) の可視性に従属するため、
 * {@link #evaluateCustom} で {@link ContentVisibilityChecker#canView} への委譲が正しく行われるかを重点確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationCommentVisibilityResolver — 単体テスト")
class CirculationCommentVisibilityResolverTest {

    @Mock
    private CirculationCommentRepository commentRepository;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @Mock
    private RecursionDepthCounter recursionDepthCounter;

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    private VisibilityMetrics visibilityMetrics;
    private CirculationCommentVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new CirculationCommentVisibilityResolver(
                commentRepository,
                contentVisibilityChecker,
                recursionDepthCounter,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,    // FollowBatchService 不要
                null);   // AuditLogService 不要（テスト）
    }

    @Test
    @DisplayName("referenceType() は COMMENT を返す")
    void referenceType_is_COMMENT() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.COMMENT);
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
            verifyNoInteractions(commentRepository);
            verifyNoInteractions(contentVisibilityChecker);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("存在しない commentId は false（loadProjections が空を返す）")
        void canView_unknownId_false() {
            when(commentRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
            verifyNoInteractions(contentVisibilityChecker);
        }
    }

    // -------------------------------------------------------------------------
    // 親文書への委譲判定
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("親文書委譲判定")
    class ParentDelegation {

        @Test
        @DisplayName("checker.canView(CIRCULATION_DOCUMENT, documentId, viewerUserId) が true → true")
        void checker_true_returns_true() {
            Long commentId = 1L;
            Long documentId = 10L;
            Long viewerUserId = 5L;
            Long teamId = 100L;

            CirculationCommentVisibilityProjection p =
                    new CirculationCommentVisibilityProjection(commentId, "TEAM", teamId, 99L, documentId);
            when(commentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", teamId), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));
            doNothing().when(recursionDepthCounter).enter();
            when(contentVisibilityChecker.canView(ReferenceType.CIRCULATION_DOCUMENT, documentId, viewerUserId))
                    .thenReturn(true);

            assertThat(resolver.canView(commentId, viewerUserId)).isTrue();
            verify(recursionDepthCounter).enter();
            verify(recursionDepthCounter).exit();
        }

        @Test
        @DisplayName("checker.canView(CIRCULATION_DOCUMENT, documentId, viewerUserId) が false → false")
        void checker_false_returns_false() {
            Long commentId = 1L;
            Long documentId = 10L;
            Long viewerUserId = 5L;
            Long teamId = 100L;

            CirculationCommentVisibilityProjection p =
                    new CirculationCommentVisibilityProjection(commentId, "TEAM", teamId, 99L, documentId);
            when(commentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", teamId), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));
            doNothing().when(recursionDepthCounter).enter();
            when(contentVisibilityChecker.canView(ReferenceType.CIRCULATION_DOCUMENT, documentId, viewerUserId))
                    .thenReturn(false);

            assertThat(resolver.canView(commentId, viewerUserId)).isFalse();
            verify(recursionDepthCounter).enter();
            verify(recursionDepthCounter).exit();
        }

        @Test
        @DisplayName("viewerUserId=null → evaluateCustom 手前で false（checker が呼ばれない）")
        void userId_null_false_without_checker_call() {
            Long commentId = 1L;
            Long documentId = 10L;
            Long teamId = 100L;

            CirculationCommentVisibilityProjection p =
                    new CirculationCommentVisibilityProjection(commentId, "TEAM", teamId, 99L, documentId);
            when(commentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(commentId, null)).isFalse();
            verify(contentVisibilityChecker, never()).canView(any(), any(), any());
            verify(recursionDepthCounter, never()).enter();
        }

        @Test
        @DisplayName("documentId=null の Projection → false（fail-closed）")
        void documentId_null_projection_false() {
            Long commentId = 1L;
            Long teamId = 100L;

            // documentId が null の Projection
            CirculationCommentVisibilityProjection p =
                    new CirculationCommentVisibilityProjection(commentId, "TEAM", teamId, 99L, null);
            when(commentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(commentId, 5L)).isFalse();
            verify(contentVisibilityChecker, never()).canView(any(), any(), any());
            verify(recursionDepthCounter, never()).enter();
        }

        @Test
        @DisplayName("再帰深度超過: RecursionDepthCounter.enter() が IllegalStateException を投げたら伝播する")
        void recursion_depth_exceeded_propagates() {
            Long commentId = 1L;
            Long documentId = 10L;
            Long viewerUserId = 5L;
            Long teamId = 100L;

            CirculationCommentVisibilityProjection p =
                    new CirculationCommentVisibilityProjection(commentId, "TEAM", teamId, 99L, documentId);
            when(commentRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", teamId), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));
            doThrow(new IllegalStateException("recursion depth exceeded"))
                    .when(recursionDepthCounter).enter();

            assertThatThrownBy(() -> resolver.canView(commentId, viewerUserId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("recursion depth exceeded");

            // enter() が例外を投げた場合は try ブロックに入らないため exit は呼ばれない（正常な動作）
            verify(recursionDepthCounter).enter();
            verify(recursionDepthCounter, never()).exit();
        }
    }
}
