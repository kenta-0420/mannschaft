package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AC-15 戦略シーム（横展開の証明）を固定する<b>ユニットテスト</b>（Spring context 不要・高速）。
 *
 * <p>擬似 scope_type（{@code TEST_SCOPE}）の {@link FanoutRecipientSource} をテスト内で登録し、
 * <b>ジョブ表もワーカーも一切変えずに</b>その scope のジョブを配信できる（村実装に依存しない）ことを固定する。</p>
 *
 * <h2>2 段の主張</h2>
 * <ul>
 *   <li><b>seam プランビング（現行 green）</b>: {@link FanoutRecipientSourceRegistry} が擬似ソースを
 *       {@code scope_type} で解決できる。これは seam が存在することの証明（{@link #registryResolvesArbitraryScopeType()}）。</li>
 *   <li><b>ワーカーが seam 経由で配信</b>: 擬似ソースから受信者を得て、非数値 scope_ref を解釈せず
 *       SYSTEM/null の既定バルク経路で配送し、失敗記録なしで DONE に進む
 *       （{@link #workerDeliversViaArbitraryScopeSourceWithoutVillage()}）。</li>
 * </ul>
 */
@DisplayName("AC-15 fan-out 戦略シーム（横展開）ユニット試練")
class NotificationFanoutStrategySeamTest {

    private static final String TEST_SCOPE = "TEST_SCOPE";
    private static final String SCOPE_REF = "external-scope-reference";

    /** 村実装に依存しない擬似受信者ソース（横展開の証明用）。 */
    private static final class FakeRecipientSource implements FanoutRecipientSource {
        final AtomicInteger calls = new AtomicInteger();
        private final List<Long> all;

        FakeRecipientSource(List<Long> all) {
            this.all = all;
        }

        @Override
        public String scopeType() {
            return TEST_SCOPE;
        }

        @Override
        public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
            calls.incrementAndGet();
            return all.stream()
                    .filter(id -> id > request.cursorSubjectId())
                    .limit(request.limit())
                    // Issue #2871: 受信者は user_id ＋ locale の組。擬似ソースは既定ロケールで返す。
                    .map(id -> new FanoutRecipient(id, "ja"))
                    .toList();
        }
    }

    @Test
    @DisplayName("AC-15(seam): レジストリは擬似 scope_type の受信者ソースを解決する（村実装非依存・現行 green）")
    void registryResolvesArbitraryScopeType() {
        FakeRecipientSource fake = new FakeRecipientSource(List.of(1L, 2L, 3L));
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(fake));

        Optional<FanoutRecipientSource> resolved = registry.resolve(TEST_SCOPE);

        assertThat(resolved).as("擬似 scope_type がレジストリで解決できる（seam 存在の証明）").containsSame(fake);
        assertThat(resolved.get().nextPage(new FanoutPageRequest(SCOPE_REF, 0L, 10, true, 0, 1)))
                .as("擬似ソースはキーセットで受信者を返す")
                .extracting(FanoutRecipient::userId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("AC-15(worker)/#3894 AC08: 非数値の擬似 scope は SYSTEM/null で配送完了する")
    void workerDeliversViaArbitraryScopeSourceWithoutVillage() {
        FakeRecipientSource fake = new FakeRecipientSource(List.of(10L, 20L, 30L));
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(fake));

        NotificationFanoutJobService jobService = mock(NotificationFanoutJobService.class);
        NotificationBulkFanoutService bulkFanoutService = mock(NotificationBulkFanoutService.class);
        // Issue #2871: ワーカーはロケール別文面の子表を引く。擬似ジョブぶんの 1 行を返すモックを渡す。
        NotificationFanoutJobMessageRepository jobMessageRepository =
                mock(NotificationFanoutJobMessageRepository.class);
        given(jobMessageRepository.findByJobId(org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(NotificationFanoutJobMessage.builder()
                        .locale("ja").title("擬似 scope 配信").body(null).build()));
        // CMP-260920-1040: chunkSinks は本テストの scope（TEST_SCOPE/TEST_TYPE）に一致する実装が無いため、
        // 空リストを渡す＝resolveChunkSink が null を返し、従来の bulkFanoutService 経路のまま配信される
        // （このテストの意図＝村実装非依存の seam 経路を保つ）。
        NotificationFanoutWorker worker =
                new NotificationFanoutWorker(registry, jobService, bulkFanoutService, List.of(), jobMessageRepository);

        NotificationFanoutJob job = NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(TEST_SCOPE)
                .scopeRef(SCOPE_REF)
                .notificationType("TEST_TYPE")
                .priority(NotificationPriority.NORMAL)
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // done 条件: ワーカーは村実装に触れず、レジストリで解決した擬似ソースから受信者を得て配信する。
        worker.processOne(job);

        assertThat(fake.calls.get())
                .as("AC-15: ワーカーは seam（擬似ソース）経由で受信者を取得して配信する（村実装非依存）")
                .isGreaterThan(0);
        verify(bulkFanoutService).insertAndDispatchChunk(any(), eq("TEST_TYPE"), eq(NotificationPriority.NORMAL),
                isNull(), isNull(), eq(NotificationScopeType.SYSTEM), isNull(), isNull(), isNull(), isNull());
        verify(jobService).markDone(job.getId());
        verify(jobService, never()).recordFailure(any(), any(), anyInt());
        verify(jobService, never()).recordFailure(any(), any(), anyInt(), anyBoolean());
    }
}
