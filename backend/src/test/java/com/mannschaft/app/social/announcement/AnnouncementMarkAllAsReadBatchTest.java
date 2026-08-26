package com.mannschaft.app.social.announcement;

import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AnnouncementReadService#markAllAsRead} の件数上限・チャンク分割の単体テスト（#2494 / #2530）。
 *
 * <p><b>本テストが固定する不変条件</b>:</p>
 * <ul>
 *   <li>対象抽出は<b>「可視かつ未読」を DB 側で絞るクエリ</b>
 *       （{@link AnnouncementFeedQueryRepository#findUnreadIdsByScope}）だけを使い、
 *       <b>スコープ内 feed の全件取得を一切行わない</b>
 *       （{@code AnnouncementFeedRepository} に一度も触れないことを検証する）。</li>
 *   <li>1 クエリ / 1 {@code INSERT} 文の件数が
 *       {@link AnnouncementReadService#MARK_ALL_BATCH_SIZE} を超えない
 *       （{@code IN} 句のプレースホルダ上限・{@code max_allowed_packet} に触れない）。</li>
 *   <li>チャンクをまたいでも<b>1 リクエストで完結</b>する。</li>
 *   <li>コストが<b>未読件数</b>にのみ比例する（未読 0 件なら、スコープの feed が何万件あっても
 *       クエリ 1 回・{@code INSERT} 0 回で終わる）。</li>
 *   <li>可視性集合が空（fail-closed のスコープ種別）なら DB に一切触れない。</li>
 *   <li><b>#2530 ②</b> — 2 周目以降は前チャンクの最大 ID を {@code lastSeenId} カーソルとして渡し、
 *       毎回先頭から引き直さない（総インデックスプローブ数の線形化）。</li>
 *   <li><b>#2530 ①</b> — 戻り値が「実際に既読化した件数」と「未読が残っているか」の
 *       2 つを伝える（防御上限に達したときに嘘の 0 件を返さない）。</li>
 *   <li><b>#2530 ⑤</b> — 既読行の作成は DB 側で冪等な UPSERT 経路
 *       （{@link AnnouncementReadStatusRepository#insertReadStatusesIgnoringExisting}）を通り、
 *       {@code saveAll} による素の {@code INSERT} は使わない。</li>
 * </ul>
 *
 * <p>可視性そのものの固定は実 HTTP + 実 MySQL の
 * {@link SocialAnnouncementScopeContractIT}（#2478 の回帰ガード）と
 * {@code AnnouncementReadUnreadOnlyBulkIT} が担う。本テストは「件数の振る舞い」に特化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementReadService 一括既読の件数上限・チャンク分割（#2494 / #2530）")
class AnnouncementMarkAllAsReadBatchTest {

    private static final Long TEAM_ID = 42L;
    private static final Long USER_ID = 7L;

    /** MEMBER 閲覧者が見られる visibility 集合（一覧側と同一の正準経路が算出する値）。 */
    private static final Set<String> MEMBER_VISIBILITIES =
            AnnouncementVisibility.allowedFor(ViewerRole.MEMBER.name());

    @Mock
    private AnnouncementFeedRepository feedRepository;

    @Mock
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Mock
    private AnnouncementReadStatusRepository readStatusRepository;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private AnnouncementCreationService creationService;

    @Mock
    private RoleResolver roleResolver;

    @InjectMocks
    private AnnouncementReadService readService;

    /** {@code from} から {@code count} 件の連番 ID リストを作る。 */
    private static List<Long> ids(long from, int count) {
        return LongStream.range(from, from + count).boxed().toList();
    }

    private void givenMemberViewer() {
        given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
    }

    /** 初回チャンク（カーソル無し）の取得をスタブする。 */
    private org.mockito.BDDMockito.BDDMyOngoingStubbing<List<Long>> givenFirstChunk() {
        return given(feedQueryRepository.findUnreadIdsByScope(
                eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(MEMBER_VISIBILITIES), eq(USER_ID),
                isNull(), eq(AnnouncementReadService.MARK_ALL_BATCH_SIZE)));
    }

    /** カーソルを問わない全チャンク取得をスタブする。 */
    private org.mockito.BDDMockito.BDDMyOngoingStubbing<List<Long>> givenAnyChunk() {
        return given(feedQueryRepository.findUnreadIdsByScope(
                any(), anyLong(), anySet(), anyLong(), any(), anyInt()));
    }

    @Nested
    @DisplayName("チャンク分割（1リクエストで完結・上限を超えない）")
    class Chunking {

        @Test
        @DisplayName("未読がチャンク未満なら クエリ1回・UPSERT 1回で完結する")
        void 単一チャンクで完結する() {
            givenMemberViewer();
            List<Long> unread = ids(1000L, 120);
            givenFirstChunk().willReturn(unread);

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(120);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readStatusRepository, times(1)).insertReadStatusesIgnoringExisting(eq(USER_ID), any());
            // 「スコープ内 feed 全件取得」の経路は完全に消えている
            verifyNoInteractions(feedRepository);
        }

        @Test
        @DisplayName("未読が2チャンクにまたがっても1リクエストで完結し、1回の取得件数は上限以下")
        void 複数チャンクをまたいでも1リクエストで完結する() {
            givenMemberViewer();
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            List<Long> second = ids(10_000L, 137);
            givenAnyChunk().willReturn(first).willReturn(second);

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount())
                    .isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE + 137);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());

            // 各 INSERT の件数が上限を超えていないこと（SQL の上限に触れない根拠）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(readStatusRepository, times(2))
                    .insertReadStatusesIgnoringExisting(eq(USER_ID), captor.capture());
            for (Collection<Long> batch : captor.getAllValues()) {
                assertThat(batch.size()).isLessThanOrEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            }
        }

        @Test
        @DisplayName("未読がちょうどチャンク境界ちょうどなら、空の追加クエリで終端を確認して完結する")
        void チャンク境界ちょうどでも取りこぼさない() {
            givenMemberViewer();
            givenAnyChunk()
                    .willReturn(ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(List.of());

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readStatusRepository, times(1)).insertReadStatusesIgnoringExisting(eq(USER_ID), any());
        }

        @Test
        @DisplayName("チャンク境界+1件でも2周目で1件だけ拾って完結する（取りこぼしゼロ）")
        void チャンク境界を1件超えても取りこぼさない() {
            givenMemberViewer();
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            givenAnyChunk().willReturn(first).willReturn(List.of(9_999L));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount())
                    .isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE + 1);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("#2530 ② カーソル（lastSeenId）で再スキャンを線形化する")
    class CursorLinearization {

        @Test
        @DisplayName("初回はカーソル無し、2周目は直前チャンクの最大 ID をカーソルに渡す")
        void 二周目はカーソルを渡す() {
            givenMemberViewer();
            // ID 昇順の 500 件（最大 ID = 500）→ 2 周目は id > 500 だけを引く
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            Long expectedCursor = first.get(first.size() - 1);
            givenAnyChunk().willReturn(first).willReturn(ids(600L, 10));

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            // 1 周目: カーソル無し（先頭から）
            verify(feedQueryRepository).findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    null, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            // 2 周目: 直前チャンクの最大 ID をカーソルに渡す（既読化済みの行を二度読まない）
            verify(feedQueryRepository).findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    expectedCursor, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
        }

        @Test
        @DisplayName("カーソルは単調増加する（3周分でも先頭に巻き戻らない）")
        void カーソルは単調増加する() {
            givenMemberViewer();
            int size = AnnouncementReadService.MARK_ALL_BATCH_SIZE;
            givenAnyChunk()
                    .willReturn(ids(1L, size))
                    .willReturn(ids(1_000L, size))
                    .willReturn(ids(5_000L, 3));

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            ArgumentCaptor<Long> cursorCaptor = ArgumentCaptor.forClass(Long.class);
            verify(feedQueryRepository, times(3)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), cursorCaptor.capture(), anyInt());

            List<Long> cursors = cursorCaptor.getAllValues();
            assertThat(cursors.get(0)).as("初回は先頭から（カーソル無し）").isNull();
            assertThat(cursors.get(1)).isEqualTo(size);            // 1 周目の最大 ID
            assertThat(cursors.get(2)).isEqualTo(1_000L + size - 1); // 2 周目の最大 ID
        }
    }

    @Nested
    @DisplayName("#2530 ① 防御上限に達したことを呼び出し元に伝える")
    class TruncationIsReported {

        @Test
        @DisplayName("防御上限に達したら無限ループせず打ち切り、未読が残っていることを伝える")
        void 防御上限で打ち切り残余を伝える() {
            givenMemberViewer();
            // 常に満杯のチャンクを返す（＝未読が減らない病的ケース）
            givenAnyChunk().willReturn(ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE
                            * AnnouncementReadService.MARK_ALL_MAX_BATCHES);
            assertThat(outcome.hasMoreUnread())
                    .as("打ち切ったのに『残りなし』と嘘をついてはならない（#2530 ①）")
                    .isTrue();
            // 上限ぶんのチャンク取得 + 残余確認の 1 回
            verify(feedQueryRepository, times(AnnouncementReadService.MARK_ALL_MAX_BATCHES + 1))
                    .findUnreadIdsByScope(any(), anyLong(), anySet(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("上限ちょうどで未読が尽きていたら『残りあり』とは言わない（残余確認で裏を取る）")
        void 上限ちょうどで尽きたら残りなしと報告する() {
            givenMemberViewer();
            int size = AnnouncementReadService.MARK_ALL_BATCH_SIZE;
            int max = AnnouncementReadService.MARK_ALL_MAX_BATCHES;
            org.mockito.BDDMockito.BDDMyOngoingStubbing<List<Long>> stub = givenAnyChunk();
            for (int i = 0; i < max; i++) {
                stub = stub.willReturn(ids(1L + (long) i * size, size));
            }
            // 上限到達後の残余確認: 未読はもう無い
            stub.willReturn(List.of());

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(size * max);
            assertThat(outcome.hasMoreUnread())
                    .as("ちょうど尽きた場合に残余ありと誤報してはならない")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("コストが未読件数にのみ比例する")
    class ProportionalToUnreadOnly {

        @Test
        @DisplayName("未読 0 件なら feed 総数に関係なくクエリ1回・INSERT 0回で終わる")
        void 未読ゼロならINSERTしない() {
            givenMemberViewer();
            givenFirstChunk().willReturn(List.of());

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isZero();
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readStatusRepository, never()).insertReadStatusesIgnoringExisting(anyLong(), any());
            // 既読済みの引き当てのための IN 句クエリも発行しない
            verify(readStatusRepository, never()).findByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
            verifyNoInteractions(feedRepository);
        }

        @Test
        @DisplayName("既読済み ID を IN 句で引き当てる旧経路は使わない")
        void 既読引き当ての旧IN句経路を使わない() {
            givenMemberViewer();
            givenFirstChunk().willReturn(ids(1L, 10));

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            verify(readStatusRepository, never()).findByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
            verify(readStatusRepository, never()).countByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("保存内容と fail-closed")
    class SavedRowsAndFailClosed {

        @Test
        @DisplayName("#2530 ⑤ 既読行の作成は DB 側で冪等な UPSERT を通す（素の saveAll は使わない）")
        void 冪等なUPSERT経路を通す() {
            givenMemberViewer();
            List<Long> unread = ids(500L, 3);
            givenFirstChunk().willReturn(unread);

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(readStatusRepository).insertReadStatusesIgnoringExisting(eq(USER_ID), captor.capture());
            assertThat(captor.getValue()).containsExactlyElementsOf(unread);

            // 素の INSERT（saveAll）は同時実行で UNIQUE 違反 → 500 になるため使ってはならない
            verify(readStatusRepository, never()).saveAll(any());
            verify(readStatusRepository, never()).save(any());
        }

        @Test
        @DisplayName("可視性集合が空になるスコープ種別（COMMITTEE）では DB に一切触れない")
        void failClosedスコープではDBに触れない() {
            AnnouncementReadService.MarkAllReadOutcome outcome =
                    readService.markAllAsRead(AnnouncementScopeType.COMMITTEE, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isZero();
            assertThat(outcome.hasMoreUnread()).isFalse();
            verifyNoInteractions(feedQueryRepository);
            verifyNoInteractions(readStatusRepository);
            verifyNoInteractions(feedRepository);
            verifyNoInteractions(roleResolver);
        }
    }
}
