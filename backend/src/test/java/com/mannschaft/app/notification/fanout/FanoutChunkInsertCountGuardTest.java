package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2871 の番人（AC-3 / AC-4）。
 *
 * <h2>AC-3: 1 チャンクにつき INSERT は 1 回（ロケール数に比例させない）</h2>
 * <p>ロケール別配信を「チャンクを locale ごとに分割して INSERT する」形で実装すると、
 * 1 チャンクあたりの INSERT 文数が 1 → 最大 6 に増え、書き込み経路が細分化する。
 * 現状 190 件/秒で SLO 未達の局面ではこれは致命的な後退であり、かつ at-least-once の
 * 重複上限（クラッシュ時に再送されうる件数）がチャンク単位で崩れる。
 * そこで「500 人・6 ロケール混在のチャンクでも
 * {@link NotificationBulkFanoutService#insertAndDispatchChunk} はちょうど 1 回」を機械的に固定する。</p>
 *
 * <h2>AC-4: 再開カーソルは user_id ただ 1 本</h2>
 * <p>locale をカーソルへ混ぜると、配信途中に利用者が言語を切り替えたときに
 * 「重複」または「欠落」が起きる。カーソル前進が常に「そのページ末尾の user_id」であること、
 * かつ INSERT 確定の<b>後</b>にのみ行われることを固定する。</p>
 */
@DisplayName("fan-out チャンク INSERT 回数・カーソル番人（Issue #2871・AC-3 / AC-4）")
class FanoutChunkInsertCountGuardTest {

    private static final String SCOPE = "GUARD_SCOPE";
    private static final String SCOPE_REF = "1";

    /** 500 人・6 ロケール混在の受信者を 1 ページだけ返す擬似ソース（2 回目は空＝DONE）。 */
    private static final class MixedLocaleSource implements FanoutRecipientSource {
        private final List<FanoutRecipient> all;

        MixedLocaleSource(int population) {
            List<FanoutRecipient> list = new ArrayList<>(population);
            for (int i = 1; i <= population; i++) {
                // 6 種の配信ロケールを順繰りに割り当てる（1 チャンク内に全ロケールが混在する）。
                String tag = DeliveryLocales.TAGS.get(i % DeliveryLocales.TAGS.size());
                list.add(new FanoutRecipient(i, tag));
            }
            this.all = List.copyOf(list);
        }

        @Override
        public String scopeType() {
            return SCOPE;
        }

        @Override
        public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
            return all.stream()
                    .filter(r -> r.userId() > request.cursorSubjectId())
                    .limit(request.limit())
                    .toList();
        }
    }

    private static List<NotificationFanoutJobMessage> sixLocaleMessages() {
        List<NotificationFanoutJobMessage> rows = new ArrayList<>();
        for (String tag : DeliveryLocales.TAGS) {
            rows.add(NotificationFanoutJobMessage.builder()
                    .locale(tag)
                    .title("title-" + tag)
                    .body("body-" + tag)
                    .build());
        }
        return rows;
    }

    private static NotificationFanoutJob job() {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(SCOPE)
                .scopeRef(SCOPE_REF)
                .notificationType("GUARD_TYPE")
                .priority(NotificationPriority.NORMAL)
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .shardIndex((short) 0)
                .shardCount((short) 1)
                .includeSupporters(true)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    @DisplayName("AC-3: 500 人・6 ロケール混在の 1 チャンクでも insertAndDispatchChunk はちょうど 1 回")
    @SuppressWarnings("unchecked")
    void ロケールが混在してもチャンクあたりのINSERTは1回() {
        // given: 母集団 500 人（= CHUNK_SIZE ちょうど）で 6 ロケールが混在する。
        MixedLocaleSource source = new MixedLocaleSource(500);
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(source));
        NotificationFanoutJobService jobService = mock(NotificationFanoutJobService.class);
        NotificationBulkFanoutService bulkFanoutService = mock(NotificationBulkFanoutService.class);
        NotificationFanoutJobMessageRepository jobMessageRepository =
                mock(NotificationFanoutJobMessageRepository.class);
        given(jobMessageRepository.findByJobId(any())).willReturn(sixLocaleMessages());

        NotificationFanoutWorker worker =
                new NotificationFanoutWorker(registry, jobService, bulkFanoutService, jobMessageRepository);

        // when
        worker.processOne(job());

        // then: 500 人ぶん・6 ロケールぶんではなく、チャンク 1 個ぶんの 1 回だけ。
        ArgumentCaptor<List<NotificationBulkFanoutService.RecipientMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(bulkFanoutService, times(1)).insertAndDispatchChunk(
                captor.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        List<NotificationBulkFanoutService.RecipientMessage> rows = captor.getValue();
        assertThat(rows).as("AC-3: 1 回の呼び出しにチャンクの全受信者が入る").hasSize(500);
        assertThat(rows.stream().map(NotificationBulkFanoutService.RecipientMessage::title)
                .collect(Collectors.toSet()))
                .as("AC-3: 1 回の INSERT の中で行ごとに文面が違う（6 ロケールぶんの title が同居する）")
                .hasSize(DeliveryLocales.TAGS.size());

        // 文面がロケールと正しく対応していること（取り違えの検出）。
        Map<Long, String> localeByUserId = new java.util.HashMap<>();
        for (FanoutRecipient r : source.nextPage(new FanoutPageRequest(SCOPE_REF, 0L, 500, true, 0, 1))) {
            localeByUserId.put(r.userId(), r.locale());
        }
        for (NotificationBulkFanoutService.RecipientMessage row : rows) {
            assertThat(row.title())
                    .as("受信者 %s の文面は自分の locale のもの", row.userId())
                    .isEqualTo("title-" + localeByUserId.get(row.userId()));
        }
    }

    @Test
    @DisplayName("AC-4: カーソル前進は user_id ただ 1 本で、INSERT 確定の後にだけ起きる")
    void カーソルはuserIdのみでINSERT後に前進する() {
        // given: 母集団 1200 人（CHUNK_SIZE=500 で 3 チャンク＋空ページ）。
        MixedLocaleSource source = new MixedLocaleSource(1200);
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(source));
        NotificationFanoutJobService jobService = mock(NotificationFanoutJobService.class);
        NotificationBulkFanoutService bulkFanoutService = mock(NotificationBulkFanoutService.class);
        NotificationFanoutJobMessageRepository jobMessageRepository =
                mock(NotificationFanoutJobMessageRepository.class);
        given(jobMessageRepository.findByJobId(any())).willReturn(sixLocaleMessages());

        // INSERT が起きた回数を記録し、カーソル前進の時点で必ず「INSERT が 1 回多く済んでいる」ことを見る。
        AtomicInteger insertCount = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            insertCount.incrementAndGet();
            return null;
        }).when(bulkFanoutService).insertAndDispatchChunk(
                org.mockito.ArgumentMatchers.<List<NotificationBulkFanoutService.RecipientMessage>>any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        List<Long> advancedCursors = new ArrayList<>();
        List<Integer> insertsAtAdvance = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            advancedCursors.add(inv.getArgument(1));
            insertsAtAdvance.add(insertCount.get());
            return null;
        }).when(jobService).advanceCursor(any(), anyLong(), anyLong());

        NotificationFanoutWorker worker =
                new NotificationFanoutWorker(registry, jobService, bulkFanoutService, jobMessageRepository);

        // when
        worker.processOne(job());

        // then: カーソルは各ページ末尾の user_id（500 / 1000 / 1200）そのもの。locale は混ざらない。
        assertThat(advancedCursors)
                .as("AC-4: 再開カーソルは user_id ただ 1 本（ページ末尾の user_id）")
                .containsExactly(500L, 1000L, 1200L);
        assertThat(insertsAtAdvance)
                .as("AC-4: カーソル前進は必ず INSERT 確定の後（前進 n 回目の時点で INSERT は n 回済み）")
                .containsExactly(1, 2, 3);
        verify(bulkFanoutService, times(3)).insertAndDispatchChunk(
                org.mockito.ArgumentMatchers.<List<NotificationBulkFanoutService.RecipientMessage>>any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
