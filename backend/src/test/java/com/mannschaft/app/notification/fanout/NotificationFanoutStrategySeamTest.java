package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
 *   <li><b>ワーカーが seam 経由で配信（現行 red）</b>: ワーカーが擬似 scope のジョブを、レジストリで解決した
 *       擬似ソースから受信者を得て配信する。現行は {@link NotificationFanoutWorker#processOne} 未実装ゆえ
 *       {@link UnsupportedOperationException} で FAIL する（{@link #workerDeliversViaArbitraryScopeSourceWithoutVillage()}）。</li>
 * </ul>
 */
@DisplayName("AC-15 fan-out 戦略シーム（横展開）ユニット試練")
class NotificationFanoutStrategySeamTest {

    private static final String TEST_SCOPE = "TEST_SCOPE";
    private static final long SCOPE_ID = 4242L;

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
        public List<Long> nextPage(long scopeId, long cursorSubjectId, int limit) {
            calls.incrementAndGet();
            return all.stream().filter(id -> id > cursorSubjectId).limit(limit).toList();
        }
    }

    @Test
    @DisplayName("AC-15(seam): レジストリは擬似 scope_type の受信者ソースを解決する（村実装非依存・現行 green）")
    void registryResolvesArbitraryScopeType() {
        FakeRecipientSource fake = new FakeRecipientSource(List.of(1L, 2L, 3L));
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(fake));

        Optional<FanoutRecipientSource> resolved = registry.resolve(TEST_SCOPE);

        assertThat(resolved).as("擬似 scope_type がレジストリで解決できる（seam 存在の証明）").containsSame(fake);
        assertThat(resolved.get().nextPage(SCOPE_ID, 0L, 10))
                .as("擬似ソースはキーセットで受信者を返す").containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("AC-15(worker): ワーカーは擬似 scope のジョブを seam 経由で配信する（村実装非依存・現行 red）")
    void workerDeliversViaArbitraryScopeSourceWithoutVillage() {
        FakeRecipientSource fake = new FakeRecipientSource(List.of(10L, 20L, 30L));
        FanoutRecipientSourceRegistry registry = new FanoutRecipientSourceRegistry(List.of(fake));

        NotificationFanoutJobRepository jobRepository = mock(NotificationFanoutJobRepository.class);
        NotificationFanoutJobService jobService = mock(NotificationFanoutJobService.class);
        NotificationFanoutWorker worker = new NotificationFanoutWorker(jobRepository, registry, jobService);

        NotificationFanoutJob job = NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(TEST_SCOPE)
                .scopeId(SCOPE_ID)
                .notificationType("TEST_TYPE")
                .title("擬似 scope 配信")
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
        // 現行は processOne 未実装（UnsupportedOperationException）ゆえ、この呼び出しが FAIL=red。
        worker.processOne(job);

        // green の暁には擬似ソースの nextPage が消費される（村 Repository を一切呼ばずに配信できる）。
        assertThat(fake.calls.get())
                .as("AC-15: ワーカーは seam（擬似ソース）経由で受信者を取得して配信する（村実装非依存）")
                .isGreaterThan(0);
    }
}
