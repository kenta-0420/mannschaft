package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.RecursionDepthCounter;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03.16 {@link ScheduleCommentVisibilityResolver} の単体テスト。
 *
 * <p>受け入れ条件: <b>AC-36</b>（fail-closed・設計書 §9.4）および
 * §4.5.0 の「判定は {@code canView} 単体へ一本化する」という構造そのものの固定。</p>
 */
@DisplayName("F03.16 ScheduleCommentVisibilityResolver（可視性は親予定へ完全委譲）")
class ScheduleCommentVisibilityResolverTest {

    private ScheduleCommentRepository repository;
    private ContentVisibilityChecker checker;
    private RecursionDepthCounter depthCounter;
    private ScheduleCommentVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ScheduleCommentRepository.class);
        checker = Mockito.mock(ContentVisibilityChecker.class);
        depthCounter = Mockito.mock(RecursionDepthCounter.class);
        resolver = new ScheduleCommentVisibilityResolver(repository, checker, depthCounter);
    }

    /** {@link ScheduleCommentVisibilityProjection} の最小実装（射影 IF はインターフェースのため）。 */
    private static ScheduleCommentVisibilityProjection projection(UUID id, Long scheduleId) {
        return new ScheduleCommentVisibilityProjection() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public Long getScheduleId() {
                return scheduleId;
            }
        };
    }

    @Test
    @DisplayName("referenceType は SCHEDULE_COMMENT（COMMENT を流用しない・§4.5.1）")
    void referenceTypeはScheduleCommentを返す() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.SCHEDULE_COMMENT);
    }

    @Test
    @DisplayName("SCHEDULE_COMMENT の idKind は UUID_V7（BIGINT に落ちると全コメントが静かに不可視になる）")
    void idKindはUuidV7である() {
        // ReferenceType.idKind() は default -> BIGINT。case を書き忘れると
        // ContentVisibilityChecker.canViewUuid が fail-closed して機能が丸ごと死ぬ。
        assertThat(ReferenceType.SCHEDULE_COMMENT.idKind())
                .isEqualTo(ReferenceType.IdKind.UUID_V7);
    }

    @Nested
    @DisplayName("AC-36 fail-closed")
    class FailClosed {

        @Test
        @DisplayName("row が null なら false（例外を投げて 500 にしない・true に倒さない）")
        void rowがnullならfalse() {
            assertThat(resolver.evaluateCustom(null, 1L)).isFalse();
            verify(checker, never()).canView(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("row.scheduleId が null なら false")
        void scheduleIdがnullならfalse() {
            assertThat(resolver.evaluateCustom(projection(UUID.randomUUID(), null), 1L)).isFalse();
            verify(checker, never()).canView(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("射影が 1 件も取れなければ（コメント不存在）空集合を返す — 存在を漏らさない")
        void 射影が空なら空集合() {
            when(repository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of());
            assertThat(resolver.filterAccessibleUuid(List.of(UUID.randomUUID()), 1L)).isEmpty();
        }

        @Test
        @DisplayName("Long 経路は fail-closed（主キーは UUID のため成立しない）")
        void Long経路はfailClosed() {
            assertThat(resolver.canView(1L, 1L)).isFalse();
            assertThat(resolver.filterAccessible(List.of(1L), 1L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("§4.5.0 判定は canView 単体へ委譲する")
    class ParentDelegation {

        @Test
        @DisplayName("evaluateCustom は親 SCHEDULE の canView の結果をそのまま返す（独自述語を挟まない）")
        void 親予定のcanViewをそのまま返す() {
            UUID commentId = UUID.randomUUID();
            when(checker.canView(ReferenceType.SCHEDULE, 42L, 7L)).thenReturn(true);
            assertThat(resolver.evaluateCustom(projection(commentId, 42L), 7L)).isTrue();

            when(checker.canView(ReferenceType.SCHEDULE, 42L, 7L)).thenReturn(false);
            assertThat(resolver.evaluateCustom(projection(commentId, 42L), 7L)).isFalse();

            // 再帰深度ガードで挟まれていること（F00 §D-16）。
            verify(depthCounter, times(2)).enter();
            verify(depthCounter, times(2)).exit();
        }

        @Test
        @DisplayName("親が見えないコメントは 1 件も返さない（min_view_role の遮断は canView 内部で効く）")
        void 親が見えなければ空集合() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(repository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(a, 42L), projection(b, 42L)));
            when(checker.filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(7L)))
                    .thenReturn(Set.of());

            assertThat(resolver.filterAccessibleUuid(List.of(a, b), 7L)).isEmpty();
        }

        @Test
        @DisplayName("同一予定の複数コメントは親 scheduleId 1 件に畳んで 1 回だけ委譲する（AC-30 の構造）")
        void 同一予定のコメントは親判定1回に畳まれる() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(repository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(a, 42L), projection(b, 42L), projection(c, 42L)));
            when(checker.filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(7L)))
                    .thenReturn(Set.of(42L));

            assertThat(resolver.filterAccessibleUuid(List.of(a, b, c), 7L))
                    .containsExactlyInAnyOrder(a, b, c);

            // コメント 1 件ずつ canView を呼ぶ実装（N+1）に退行したら落ちる。
            verify(checker, times(1)).filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(7L));
            verify(checker, never()).canView(any(), anyLong(), anyLong());
            // 射影取得も 1 回だけ（SQL 1 本）。
            verify(repository, times(1)).findVisibilityProjectionsByIdIn(any());
        }

        @Test
        @DisplayName("複数予定にまたがる場合も委譲は 1 回（重複排除した scheduleId 集合を渡す）")
        void 複数予定でも委譲は1回() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(repository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(a, 42L), projection(b, 99L)));
            when(checker.filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(7L)))
                    .thenReturn(Set.of(42L));

            assertThat(resolver.filterAccessibleUuid(List.of(a, b), 7L)).containsExactly(a);
            verify(checker, times(1)).filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(7L));
        }
    }
}
