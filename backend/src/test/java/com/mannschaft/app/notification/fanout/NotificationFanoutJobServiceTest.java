package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NotificationFanoutJobService} の<b>ユニット試練</b>（Mockito・MySQL 不要・高速）。
 *
 * <h2>M-1（握り潰し禁止の根治）</h2>
 * <p>enqueue の {@code catch(DataIntegrityViolationException)} が広すぎると、uk_fanout_idempotency 衝突<b>以外</b>の
 * 整合性違反（NOT NULL 等）まで「冪等 skip」として無言で握り、通知が痕跡なく消える。根治後は「当該冪等キーの
 * ジョブが実在する時だけ skip・実在しなければ rethrow」となることを固定する。</p>
 *
 * <h2>AC-10（silent drop 根絶の可観測性）</h2>
 * <p>{@code recordFailure} がリトライ加算で {@code ...job.retry}、DEAD_LETTER 転落で {@code ...job.dead_letter} を
 * 計上することを {@link SimpleMeterRegistry} で固定する。</p>
 */
@DisplayName("NotificationFanoutJobService ユニット試練（M-1 握り潰し根治 / AC-10 メトリクス）")
class NotificationFanoutJobServiceTest {

    private static final String SCOPE_TYPE = "VILLAGE";
    private static final String SCOPE_REF = "11111111-1111-1111-1111-111111111111";
    private static final String NOTIF_TYPE = "EVENT_CREATED";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> providerOf(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    // ===================================================================
    // M-1: catch は uk 衝突のみ握る（冪等キーのジョブ実在時のみ skip）
    // ===================================================================

    @Test
    @DisplayName("M-1: 整合性違反だが冪等ジョブが不在なら enqueue は例外を rethrow する（無言 skip しない）")
    void enqueue_rethrowsWhenNotIdempotentDuplicate() {
        NotificationFanoutJobRepository repo = mock(NotificationFanoutJobRepository.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        NotificationFanoutJobService service =
                new NotificationFanoutJobService(repo, txm, providerOf(new SimpleMeterRegistry()));

        // 整合性違反（例: NOT NULL 違反）を模す。
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("not-null violation"));
        // 冪等キーのジョブは存在しない → uk 衝突ではない → 握ってはいけない。
        when(repo.findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enqueue(SCOPE_TYPE, SCOPE_REF, NOTIF_TYPE, UUID.randomUUID(),
                null, "件名", "本文", NotificationPriority.NORMAL, "VILLAGE_EVENT", null, "/x", null))
                .as("M-1: uk 衝突でない整合性違反は握り潰さず露見させる")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("M-1: 冪等キーのジョブが実在するなら enqueue は例外を握って静かに skip する")
    void enqueue_skipsWhenIdempotentDuplicateExists() {
        NotificationFanoutJobRepository repo = mock(NotificationFanoutJobRepository.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        NotificationFanoutJobService service =
                new NotificationFanoutJobService(repo, txm, providerOf(new SimpleMeterRegistry()));

        UUID sourceEvent = UUID.randomUUID();
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        // 当該冪等キーのジョブは既に存在する（真の uk 衝突）→ 握って skip してよい。
        when(repo.findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                SCOPE_TYPE, SCOPE_REF, NOTIF_TYPE, sourceEvent))
                .thenReturn(Optional.of(newJob(2)));

        assertThatCode(() -> service.enqueue(SCOPE_TYPE, SCOPE_REF, NOTIF_TYPE, sourceEvent,
                null, "件名", "本文", NotificationPriority.NORMAL, "VILLAGE_EVENT", null, "/x", null))
                .as("M-1: 真の uk 衝突（二重登録）だけは静かに冪等 skip する")
                .doesNotThrowAnyException();
    }

    // ===================================================================
    // AC-10: recordFailure のメトリクス
    // ===================================================================

    @Test
    @DisplayName("AC-10: 上限未満の失敗は retry カウンタ +1・dead_letter は増えない")
    void recordFailure_incrementsRetryCounter() {
        NotificationFanoutJobRepository repo = mock(NotificationFanoutJobRepository.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationFanoutJobService service =
                new NotificationFanoutJobService(repo, txm, providerOf(registry));

        UUID jobId = UUID.randomUUID();
        when(repo.findById(jobId)).thenReturn(Optional.of(newJob(0))); // retryCount 0 → 1（< 5）

        service.recordFailure(jobId, "配信失敗", 5);

        assertThat(registry.counter(NotificationFanoutJobService.METRIC_RETRY).count())
                .as("AC-10: リトライ加算で retry カウンタ +1").isEqualTo(1.0);
        assertThat(registry.counter(NotificationFanoutJobService.METRIC_DEAD_LETTER).count())
                .as("AC-10: まだ DEAD_LETTER ではない").isEqualTo(0.0);
    }

    @Test
    @DisplayName("AC-10: リトライ上限到達で dead_letter カウンタ +1（retry も +1）")
    void recordFailure_incrementsDeadLetterCounterAtLimit() {
        NotificationFanoutJobRepository repo = mock(NotificationFanoutJobRepository.class);
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationFanoutJobService service =
                new NotificationFanoutJobService(repo, txm, providerOf(registry));

        UUID jobId = UUID.randomUUID();
        when(repo.findById(jobId)).thenReturn(Optional.of(newJob(4))); // retryCount 4 → 5（= 上限）

        service.recordFailure(jobId, "恒久失敗", 5);

        assertThat(registry.counter(NotificationFanoutJobService.METRIC_DEAD_LETTER).count())
                .as("AC-10: 上限到達で dead_letter カウンタ +1").isEqualTo(1.0);
        assertThat(registry.counter(NotificationFanoutJobService.METRIC_RETRY).count())
                .as("AC-10: 当該試行でも retry カウンタ +1").isEqualTo(1.0);
    }

    private static NotificationFanoutJob newJob(int retryCount) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(SCOPE_TYPE)
                .scopeRef(SCOPE_REF)
                .notificationType(NOTIF_TYPE)
                .title("件名")
                .priority(NotificationPriority.NORMAL)
                .status(NotificationFanoutJobStatus.RUNNING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(retryCount)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
