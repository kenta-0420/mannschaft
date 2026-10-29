package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AnnouncementReadCommandService#markAllAsRead} の件数上限・チャンク分割の単体テスト（#2494 / #2530 / ALIC-1）。
 *
 * <p>read-all の判定（役割解決・課金・走査）は、トランザクションを持たない {@link AnnouncementReadCommandService} に移った
 * （ALIC-1 §2.5）。書き込みは {@link AnnouncementReadService#markFeedIdsAsRead}（チャンクごとに 1 tx・1 本の UPSERT）。
 * 本テストは書き込み部品をモックにし、「何を何回・どの単位で書きに行くか」を固定する。</p>
 *
 * <p><b>本テストが固定する不変条件</b>:</p>
 * <ul>
 *   <li>対象抽出は<b>「可視かつ未読」を DB 側で絞るクエリ</b>（{@link AnnouncementFeedQueryRepository#findUnreadIdsByScope}）だけを使い、
 *       スコープ内 feed の全件取得を行わない（{@code AnnouncementFeedRepository} に一度も触れない）。</li>
 *   <li>1 回の書き込みの件数が {@link AnnouncementReadService#MARK_ALL_BATCH_SIZE} を超えない。</li>
 *   <li>コストが<b>未読件数</b>にのみ比例する。可視性集合が空（fail-closed のスコープ種別）なら DB・Valkey に一切触れない。</li>
 *   <li>2 周目以降は前チャンクの最大 ID を {@code lastSeenId} カーソルとして渡す（総プローブ数の線形化）。</li>
 *   <li>戻り値が「実際に既読化した件数」と「未読が残っているか」を伝える。上限に達して未読が残るときだけ走査位置を保存する。</li>
 *   <li>HIDDEN は既読にせず、終端判定は課金で除外する<b>前</b>の取得件数で行う。</li>
 * </ul>
 *
 * <p>可視性そのものの固定は実 HTTP + 実 MySQL の {@link SocialAnnouncementScopeContractIT} と
 * {@code AnnouncementReadUnreadOnlyBulkIT}、走査位置とチャンク tx は {@code AnnouncementReadAllCursorIT}・
 * {@code AnnouncementReadChunkTransactionIT} が担う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementReadCommandService 一括既読の件数上限・チャンク分割（#2494 / #2530 / ALIC-1）")
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
    private AnnouncementFeedService feedService;

    @Mock
    private AnnouncementReadService readService;

    @Mock
    private OrgAnnouncementAudienceAccess audienceAccess;

    @Mock
    private RoleResolver roleResolver;

    @Mock
    private PaymentGateService paymentGateService;

    @Mock
    private AnnouncementReadAllCursorStore cursorStore;

    @Mock
    private Clock clock;

    @InjectMocks
    private AnnouncementReadCommandService commandService;

    /** {@code from} から {@code count} 件の連番 ID リストを作る。 */
    private static List<Long> ids(long from, int count) {
        return LongStream.range(from, from + count).boxed().toList();
    }

    private void givenMemberViewer() {
        given(roleResolver.resolveViewerRoleForPreview(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
        lenient().when(paymentGateService.checkAccessBatchForPreview(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(1);
                    if (ids == null) return Map.of();
                    return ids.stream().collect(java.util.stream.Collectors.toMap(
                            id -> id, id -> new GateCheckResponse(true, false, List.of())));
                });
        // 保存された走査位置は無い（Mockito の既定値は Long に対して 0 を返すので、null を明示する）
        lenient().when(cursorStore.load(any(), any(), any())).thenReturn(null);
        // 書き込み部品は渡された件数をそのまま「新たに既読になった件数」として返す
        lenient().when(readService.markFeedIdsAsRead(eq(USER_ID), any())).thenAnswer(invocation ->
                ((Collection<?>) invocation.getArgument(1)).size());
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
        @DisplayName("一括既読はHIDDENを除外し、FULLとLOCKEDだけを既読化する")
        void hiddenRowsAreNotMarkedRead() {
            givenMemberViewer();
            givenFirstChunk().willReturn(List.of(30L, 31L, 32L));
            given(paymentGateService.checkAccessBatchForPreview(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                    .willReturn(java.util.Map.of(
                            30L, new GateCheckResponse(false, true, List.of()),
                            31L, new GateCheckResponse(false, false, List.of()),
                            32L, new GateCheckResponse(true, false, List.of())));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(2);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(readService).markFeedIdsAsRead(eq(USER_ID), captor.capture());
            assertThat(captor.getValue()).containsExactly(31L, 32L);
            verify(paymentGateService).checkAccessBatchForPreview(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class));
        }

        @Test
        @DisplayName("未読がチャンク未満なら クエリ1回・書き込み1回で完結し、走査位置を消す")
        void 単一チャンクで完結する() {
            givenMemberViewer();
            List<Long> unread = ids(1000L, 120);
            givenFirstChunk().willReturn(unread);

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(120);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readService, times(1)).markFeedIdsAsRead(eq(USER_ID), any());
            verify(cursorStore).clear(USER_ID, AnnouncementScopeType.TEAM, TEAM_ID);
            // 「スコープ内 feed 全件取得」の経路は完全に消えている
            verifyNoInteractions(feedRepository);
        }

        @Test
        @DisplayName("未読が2チャンクにまたがっても1リクエストで完結し、1回の書き込み件数は上限以下")
        void 複数チャンクをまたいでも1リクエストで完結する() {
            givenMemberViewer();
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            List<Long> second = ids(10_000L, 137);
            givenAnyChunk().willReturn(first).willReturn(second);

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount())
                    .isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE + 137);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());

            // 各書き込みの件数が上限を超えていないこと（SQL の上限に触れない根拠）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(readService, times(2)).markFeedIdsAsRead(eq(USER_ID), captor.capture());
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
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readService, times(1)).markFeedIdsAsRead(eq(USER_ID), any());
        }

        @Test
        @DisplayName("チャンク境界+1件でも2周目で1件だけ拾って完結する（取りこぼしゼロ）")
        void チャンク境界を1件超えても取りこぼさない() {
            givenMemberViewer();
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            givenAnyChunk().willReturn(first).willReturn(List.of(9_999L));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount())
                    .isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE + 1);
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("最初の500件に HIDDEN が混じっても、課金で除外する前の件数で終端判定し後続を取り残さない（r4 D12）")
        void HIDDEN混在でも終端を取り違えない() {
            givenMemberViewer();
            int size = AnnouncementReadService.MARK_ALL_BATCH_SIZE;
            List<Long> first = ids(1L, size);
            // 先頭の1件だけ HIDDEN
            given(paymentGateService.checkAccessBatchForPreview(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any(Map.class)))
                    .willAnswer(invocation -> {
                        Collection<Long> ids = invocation.getArgument(1);
                        return ids.stream().collect(java.util.stream.Collectors.toMap(
                                id -> id, id -> new GateCheckResponse(false, id == 1L, List.of())));
                    });
            givenAnyChunk().willReturn(first).willReturn(List.of(9_999L));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).as("HIDDEN の1件を除く499件 + 後続の1件").isEqualTo(size - 1 + 1);
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

            commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

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

            commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            ArgumentCaptor<Long> cursorCaptor = ArgumentCaptor.forClass(Long.class);
            verify(feedQueryRepository, times(3)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), cursorCaptor.capture(), anyInt());

            List<Long> cursors = cursorCaptor.getAllValues();
            assertThat(cursors.get(0)).as("初回は先頭から（カーソル無し）").isNull();
            assertThat(cursors.get(1)).isEqualTo(size);            // 1 周目の最大 ID
            assertThat(cursors.get(2)).isEqualTo(1_000L + size - 1); // 2 周目の最大 ID
        }

        @Test
        @DisplayName("サーバ側に保存された走査位置があれば、その後ろから走査を始める（r6）")
        void 保存された位置から再開する() {
            givenMemberViewer();
            given(cursorStore.load(USER_ID, AnnouncementScopeType.TEAM, TEAM_ID)).willReturn(777L);
            given(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    777L, AnnouncementReadService.MARK_ALL_BATCH_SIZE)).willReturn(List.of(800L));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(1);
            verify(cursorStore).clear(USER_ID, AnnouncementScopeType.TEAM, TEAM_ID);
        }
    }

    @Nested
    @DisplayName("#2530 ① 防御上限に達したことを呼び出し元に伝える")
    class TruncationIsReported {

        @Test
        @DisplayName("防御上限に達したら無限ループせず打ち切り、未読が残っていることを伝えて走査位置を保存する")
        void 防御上限で打ち切り残余を伝える() {
            givenMemberViewer();
            // 常に満杯のチャンクを返す（＝未読が減らない病的ケース）
            givenAnyChunk().willReturn(ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE));

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE
                            * AnnouncementReadService.MARK_ALL_MAX_BATCHES);
            assertThat(outcome.hasMoreUnread())
                    .as("打ち切ったのに『残りなし』と嘘をついてはならない（#2530 ①）")
                    .isTrue();
            // 上限ぶんのチャンク取得 + 残余確認の 1 回
            verify(feedQueryRepository, times(AnnouncementReadService.MARK_ALL_MAX_BATCHES + 1))
                    .findUnreadIdsByScope(any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(cursorStore).save(USER_ID, AnnouncementScopeType.TEAM, TEAM_ID,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            verify(cursorStore, never()).clear(anyLong(), any(), anyLong());
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
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isEqualTo(size * max);
            assertThat(outcome.hasMoreUnread())
                    .as("ちょうど尽きた場合に残余ありと誤報してはならない")
                    .isFalse();
            verify(cursorStore, never()).save(anyLong(), any(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("コストが未読件数にのみ比例する")
    class ProportionalToUnreadOnly {

        @Test
        @DisplayName("未読 0 件なら feed 総数に関係なくクエリ1回・書き込み0回で終わる")
        void 未読ゼロなら書き込まない() {
            givenMemberViewer();
            givenFirstChunk().willReturn(List.of());

            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isZero();
            assertThat(outcome.hasMoreUnread()).isFalse();
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), any(), anyInt());
            verify(readService, never()).markFeedIdsAsRead(anyLong(), any());
            verifyNoInteractions(feedRepository);
        }
    }

    @Nested
    @DisplayName("fail-closed")
    class FailClosed {

        @Test
        @DisplayName("可視性集合が空になるスコープ種別（COMMITTEE）では DB にも Valkey にも一切触れない")
        void failClosedスコープではDBに触れない() {
            AnnouncementReadService.MarkAllReadOutcome outcome =
                    commandService.markAllAsRead(AnnouncementScopeType.COMMITTEE, TEAM_ID, USER_ID);

            assertThat(outcome.markedCount()).isZero();
            assertThat(outcome.hasMoreUnread()).isFalse();
            verifyNoInteractions(feedQueryRepository);
            verifyNoInteractions(readService);
            verifyNoInteractions(feedRepository);
            verifyNoInteractions(roleResolver);
            verifyNoInteractions(cursorStore);
        }

        @Test
        @DisplayName("一括課金評価の結果が null なら HIDDEN に丸めず例外にして 1 行も書かない")
        void 課金評価がnullなら書かずに失敗する() {
            given(roleResolver.resolveViewerRoleForPreview(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            lenient().when(cursorStore.load(any(), any(), any())).thenReturn(null);
            givenFirstChunk().willReturn(List.of(1L, 2L));
            given(paymentGateService.checkAccessBatchForPreview(any(), any(), any(), any())).willReturn(null);

            assertThatThrownBy(() -> commandService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID))
                    .isInstanceOf(NullPointerException.class);
            verify(readService, never()).markFeedIdsAsRead(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("単件既読の判定（HIDDEN は拒否し、書き込み部品を呼ばない）")
    class SingleRead {

        private AnnouncementFeedEntity teamFeed() {
            return AnnouncementFeedEntity.builder()
                    .scopeType(AnnouncementScopeType.TEAM).scopeId(TEAM_ID)
                    .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(1L)
                    .titleCache("単件既読の判定").visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build();
        }

        @Test
        @DisplayName("応援者に内輪限定（MEMBERS_AND_ABOVE）は ANNOUNCE_001（可視性ゲート。書き込み部品を呼ばない）")
        void 応援者に内輪限定は拒否する() {
            given(feedRepository.findById(200L)).willReturn(Optional.of(teamFeed()));
            given(roleResolver.resolveViewerRoleForPreview(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.SUPPORTER);

            assertThatThrownBy(() -> commandService.markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 200L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_001"));
            verifyNoInteractions(feedService);
        }

        @Test
        @DisplayName("別スコープのお知らせ ID は ANNOUNCE_001（越境の存在秘匿）")
        void 越境スコープは拒否する() {
            AnnouncementFeedEntity other = AnnouncementFeedEntity.builder()
                    .scopeType(AnnouncementScopeType.TEAM).scopeId(TEAM_ID + 1)
                    .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(1L).titleCache("別チーム").build();
            given(feedRepository.findById(200L)).willReturn(Optional.of(other));

            assertThatThrownBy(() -> commandService.markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 200L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_001"));
            verifyNoInteractions(feedService);
        }

        @Test
        @DisplayName("見えるお知らせ（課金も充足）は書き込み部品へ渡す")
        void 見えるお知らせは書き込みへ渡す() {
            given(feedRepository.findById(200L)).willReturn(Optional.of(teamFeed()));
            given(roleResolver.resolveViewerRoleForPreview(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(paymentGateService.checkAccess(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any()))
                    .willReturn(new GateCheckResponse(true, false, List.of()));

            commandService.markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 200L, USER_ID);

            verify(feedService).markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 200L, USER_ID);
        }

        @Test
        @DisplayName("HIDDENの単件既読は拒否し、書き込み部品を呼ばない")
        void hiddenAnnouncementIsNotMarkedRead() {
            AnnouncementFeedEntity feed = teamFeed();
            given(feedRepository.findById(200L)).willReturn(Optional.of(feed));
            given(roleResolver.resolveViewerRoleForPreview(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(paymentGateService.checkAccess(eq(ContentGateType.ANNOUNCEMENT), any(), eq(USER_ID), any()))
                    .willReturn(new GateCheckResponse(false, true, List.of()));

            assertThatThrownBy(() -> commandService.markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 200L, USER_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(feedService);
        }
    }
}
