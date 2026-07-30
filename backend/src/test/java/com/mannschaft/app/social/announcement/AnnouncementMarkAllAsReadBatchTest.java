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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AnnouncementReadService#markAllAsRead} の件数上限・チャンク分割の単体テスト（#2494）。
 *
 * <p><b>本テストが固定する不変条件</b>:</p>
 * <ul>
 *   <li>対象抽出は<b>「可視かつ未読」を DB 側で絞るクエリ</b>
 *       （{@link AnnouncementFeedQueryRepository#findUnreadIdsByScope}）だけを使い、
 *       <b>スコープ内 feed の全件取得を一切行わない</b>
 *       （{@code AnnouncementFeedRepository} に一度も触れないことを検証する）。</li>
 *   <li>1 クエリ / 1 {@code INSERT} バッチの件数が
 *       {@link AnnouncementReadService#MARK_ALL_BATCH_SIZE} を超えない
 *       （{@code IN} 句のプレースホルダ上限・{@code max_allowed_packet} に触れない）。</li>
 *   <li>チャンクをまたいでも<b>1 リクエストで完結</b>する。</li>
 *   <li>コストが<b>未読件数</b>にのみ比例する（未読 0 件なら、スコープの feed が何万件あっても
 *       クエリ 1 回・{@code INSERT} 0 回で終わる）。</li>
 *   <li>可視性集合が空（fail-closed のスコープ種別）なら DB に一切触れない。</li>
 * </ul>
 *
 * <p>可視性そのものの固定は実 HTTP + 実 MySQL の
 * {@link SocialAnnouncementScopeContractIT}（#2478 の回帰ガード）と
 * {@code AnnouncementReadUnreadOnlyBulkIT} が担う。本テストは「件数の振る舞い」に特化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementReadService 一括既読の件数上限・チャンク分割（#2494）")
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

    @Nested
    @DisplayName("チャンク分割（1リクエストで完結・上限を超えない）")
    class Chunking {

        @Test
        @DisplayName("未読がチャンク未満なら クエリ1回・INSERT 1バッチで完結する")
        void 単一チャンクで完結する() {
            givenMemberViewer();
            List<Long> unread = ids(1000L, 120);
            given(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(unread);

            int marked = readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(marked).isEqualTo(120);
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), anyInt());
            verify(readStatusRepository, times(1)).saveAll(any());
            // 「スコープ内 feed 全件取得」の経路は完全に消えている
            verifyNoInteractions(feedRepository);
        }

        @Test
        @DisplayName("未読が2チャンクにまたがっても1リクエストで完結し、1回の取得件数は上限以下")
        void 複数チャンクをまたいでも1リクエストで完結する() {
            givenMemberViewer();
            List<Long> first = ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            List<Long> second = ids(10_000L, 137);
            given(feedQueryRepository.findUnreadIdsByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(MEMBER_VISIBILITIES), eq(USER_ID),
                    eq(AnnouncementReadService.MARK_ALL_BATCH_SIZE)))
                    .willReturn(first)
                    .willReturn(second);

            int marked = readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(marked).isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE + 137);
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), anyInt());
            verify(readStatusRepository, times(2)).saveAll(any());

            // 各 INSERT バッチの件数が上限を超えていないこと（SQL の上限に触れない根拠）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AnnouncementReadStatusEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(readStatusRepository, times(2)).saveAll(captor.capture());
            for (List<AnnouncementReadStatusEntity> batch : captor.getAllValues()) {
                assertThat(batch.size()).isLessThanOrEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            }
        }

        @Test
        @DisplayName("未読がちょうどチャンク境界ちょうどなら、空の追加クエリで終端を確認して完結する")
        void チャンク境界ちょうどでも取りこぼさない() {
            givenMemberViewer();
            given(feedQueryRepository.findUnreadIdsByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(MEMBER_VISIBILITIES), eq(USER_ID),
                    eq(AnnouncementReadService.MARK_ALL_BATCH_SIZE)))
                    .willReturn(ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(List.of());

            int marked = readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(marked).isEqualTo(AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            verify(feedQueryRepository, times(2)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), anyInt());
            verify(readStatusRepository, times(1)).saveAll(any());
        }

        @Test
        @DisplayName("防御上限に達したら無限ループせず打ち切る（最大チャンク数まで）")
        void 防御上限で打ち切る() {
            givenMemberViewer();
            // 常に満杯のチャンクを返す（＝未読が減らない病的ケース）
            given(feedQueryRepository.findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), anyInt()))
                    .willReturn(ids(1L, AnnouncementReadService.MARK_ALL_BATCH_SIZE));

            int marked = readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(marked).isEqualTo(
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE * AnnouncementReadService.MARK_ALL_MAX_BATCHES);
            verify(feedQueryRepository, times(AnnouncementReadService.MARK_ALL_MAX_BATCHES))
                    .findUnreadIdsByScope(any(), anyLong(), anySet(), anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("コストが未読件数にのみ比例する")
    class ProportionalToUnreadOnly {

        @Test
        @DisplayName("未読 0 件なら feed 総数に関係なくクエリ1回・INSERT 0回で終わる")
        void 未読ゼロならINSERTしない() {
            givenMemberViewer();
            given(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(List.of());

            int marked = readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(marked).isZero();
            verify(feedQueryRepository, times(1)).findUnreadIdsByScope(
                    any(), anyLong(), anySet(), anyLong(), anyInt());
            verify(readStatusRepository, never()).saveAll(any());
            // 既読済みの引き当てのための IN 句クエリも発行しない
            verify(readStatusRepository, never()).findByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
            verifyNoInteractions(feedRepository);
        }

        @Test
        @DisplayName("既読済み ID を IN 句で引き当てる旧経路は使わない")
        void 既読引き当ての旧IN句経路を使わない() {
            givenMemberViewer();
            given(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(ids(1L, 10));

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            verify(readStatusRepository, never()).findByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
            verify(readStatusRepository, never()).countByUserIdAndAnnouncementFeedIdIn(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("保存内容と fail-closed")
    class SavedRowsAndFailClosed {

        @Test
        @DisplayName("保存される既読行は 未読 feedId × 当該ユーザー のみ")
        void 保存内容が正しい() {
            givenMemberViewer();
            List<Long> unread = ids(500L, 3);
            given(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, TEAM_ID, MEMBER_VISIBILITIES, USER_ID,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE))
                    .willReturn(unread);

            readService.markAllAsRead(AnnouncementScopeType.TEAM, TEAM_ID, USER_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AnnouncementReadStatusEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(readStatusRepository).saveAll(captor.capture());

            List<Long> savedFeedIds = new ArrayList<>();
            for (AnnouncementReadStatusEntity e : captor.getValue()) {
                assertThat(e.getUserId()).isEqualTo(USER_ID);
                savedFeedIds.add(e.getAnnouncementFeedId());
            }
            assertThat(savedFeedIds).containsExactlyElementsOf(unread);
        }

        @Test
        @DisplayName("可視性集合が空になるスコープ種別（COMMITTEE）では DB に一切触れない")
        void failClosedスコープではDBに触れない() {
            int marked = readService.markAllAsRead(AnnouncementScopeType.COMMITTEE, TEAM_ID, USER_ID);

            assertThat(marked).isZero();
            verifyNoInteractions(feedQueryRepository);
            verifyNoInteractions(readStatusRepository);
            verifyNoInteractions(feedRepository);
            verifyNoInteractions(roleResolver);
        }
    }
}
