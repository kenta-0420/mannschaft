package com.mannschaft.app.mail.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F09.18 Phase 18-e: {@link EmailOutboxMicrometerMetrics} 単体テスト。
 *
 * <p>{@link SimpleMeterRegistry} を使い Spring Context 不要で検証する。
 * {@code @PostConstruct} に相当する {@link EmailOutboxMicrometerMetrics#registerGauges()} を
 * テスト内で手動呼び出しする。</p>
 */
@DisplayName("EmailOutboxMicrometerMetrics 単体テスト")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailOutboxMicrometerMetricsTest {

    @Mock
    private EmailOutboxRepository repository;

    private SimpleMeterRegistry registry;
    private EmailOutboxMicrometerMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new EmailOutboxMicrometerMetrics(registry, repository);

        // デフォルト: ゼロ値（各テストで必要に応じてオーバーライド）
        when(repository.countByStatus(anyString())).thenReturn(0L);
        when(repository.findFirstByStatusOrderByCreatedAtAsc(anyString())).thenReturn(Optional.empty());
        when(repository.countByStatusSince(anyString(), org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(0L);
        when(repository.findAvgRetryCountOfSent()).thenReturn(null);

        metrics.registerGauges();
    }

    // -----------------------------------------------------------------------
    // §10-3: queue_depth_pending
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-3 queue_depth_pending Gauge")
    class QueueDepthPendingGauge {

        @Test
        @DisplayName("countByStatus('PENDING')=42 のとき Gauge 値が 42.0 になる")
        void queue_depth_pending_returnsCount() {
            when(repository.countByStatus(EmailOutboxStatus.PENDING.name())).thenReturn(42L);

            double val = registry.get("email_outbox.queue_depth_pending").gauge().value();
            assertThat(val).isEqualTo(42.0);
        }

        @Test
        @DisplayName("countByStatus('PENDING')=0 のとき Gauge 値が 0.0 になる")
        void queue_depth_pending_zeroCount() {
            when(repository.countByStatus(EmailOutboxStatus.PENDING.name())).thenReturn(0L);

            double val = registry.get("email_outbox.queue_depth_pending").gauge().value();
            assertThat(val).isEqualTo(0.0);
        }
    }

    // -----------------------------------------------------------------------
    // §10-4: queue_depth_dead_letter
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-4 queue_depth_dead_letter Gauge")
    class QueueDepthDeadLetterGauge {

        @Test
        @DisplayName("countByStatus('DEAD_LETTER')=3 のとき Gauge 値が 3.0 になる")
        void queue_depth_dead_letter_returnsCount() {
            when(repository.countByStatus(EmailOutboxStatus.DEAD_LETTER.name())).thenReturn(3L);

            double val = registry.get("email_outbox.queue_depth_dead_letter").gauge().value();
            assertThat(val).isEqualTo(3.0);
        }
    }

    // -----------------------------------------------------------------------
    // §10-5: oldest_pending_age_seconds
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-5 oldest_pending_age_seconds Gauge")
    class OldestPendingAgeGauge {

        @Test
        @DisplayName("5 分前に作成された PENDING entity が存在するとき Gauge 値が ≒ 300 になる")
        void oldest_pending_age_seconds_returnsElapsed() {
            EmailOutboxEntity oldestEntity = buildEntityWithCreatedAt(LocalDateTime.now().minusSeconds(300));
            when(repository.findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name()))
                    .thenReturn(Optional.of(oldestEntity));

            double val = registry.get("email_outbox.oldest_pending_age_seconds").gauge().value();
            // 実行時のわずかな時間差を許容する (±5 秒)
            assertThat(val).isCloseTo(300.0, within(5.0));
        }

        @Test
        @DisplayName("PENDING が存在しないとき Gauge 値が 0.0 になる")
        void oldest_pending_age_seconds_noPending_returnsZero() {
            when(repository.findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name()))
                    .thenReturn(Optional.empty());

            double val = registry.get("email_outbox.oldest_pending_age_seconds").gauge().value();
            assertThat(val).isEqualTo(0.0);
        }
    }

    // -----------------------------------------------------------------------
    // §10-1: delivery_success_rate
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-1 delivery_success_rate Gauge")
    class DeliverySuccessRateGauge {

        @Test
        @DisplayName("sent=9, dead=1 のとき Gauge 値が 0.9 になる")
        void delivery_success_rate_calculated() {
            when(repository.countByStatusSince(eq(EmailOutboxStatus.SENT.name()), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                    .thenReturn(9L);
            when(repository.countByStatusSince(eq(EmailOutboxStatus.DEAD_LETTER.name()), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                    .thenReturn(1L);

            double val = registry.get("email_outbox.delivery_success_rate").gauge().value();
            assertThat(val).isCloseTo(0.9, within(0.001));
        }

        @Test
        @DisplayName("sent=0, dead=0 (実績ゼロ) のとき Gauge 値が 1.0 になる")
        void delivery_success_rate_noTraffic_returnsOne() {
            when(repository.countByStatusSince(anyString(), org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                    .thenReturn(0L);

            double val = registry.get("email_outbox.delivery_success_rate").gauge().value();
            assertThat(val).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // §10-2: avg_retry_count
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-2 avg_retry_count Gauge")
    class AvgRetryCountGauge {

        @Test
        @DisplayName("findAvgRetryCountOfSent()=1.5 のとき Gauge 値が 1.5 になる")
        void avg_retry_count_returnsAvg() {
            when(repository.findAvgRetryCountOfSent()).thenReturn(1.5);

            double val = registry.get("email_outbox.avg_retry_count").gauge().value();
            assertThat(val).isCloseTo(1.5, within(0.001));
        }

        @Test
        @DisplayName("findAvgRetryCountOfSent()=null のとき Gauge 値が 0.0 になる")
        void avg_retry_count_null_returnsZero() {
            when(repository.findAvgRetryCountOfSent()).thenReturn(null);

            double val = registry.get("email_outbox.avg_retry_count").gauge().value();
            assertThat(val).isEqualTo(0.0);
        }
    }

    // -----------------------------------------------------------------------
    // §10-6: send_duration_seconds Timer
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("§10-6 send_duration_seconds Timer")
    class SendDurationTimer {

        @Test
        @DisplayName("recordSendDuration(150ms, 'VERIFICATION') 後に Timer の count が 1 になる")
        void recordSendDuration_registersTimer() {
            metrics.recordSendDuration(Duration.ofMillis(150), "VERIFICATION");

            Timer timer = registry.get("email_outbox.send_duration_seconds")
                    .tag("template_kind", "VERIFICATION")
                    .timer();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("templateKind=null のとき tag が 'unknown' になる")
        void recordSendDuration_nullTemplateKind_usesUnknown() {
            metrics.recordSendDuration(Duration.ofMillis(100), null);

            Timer timer = registry.get("email_outbox.send_duration_seconds")
                    .tag("template_kind", "unknown")
                    .timer();
            assertThat(timer.count()).isEqualTo(1L);
        }
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    /**
     * createdAt が指定値の {@link EmailOutboxEntity} を生成するヘルパー。
     * テスト用に最小限のフィールドのみ設定する。
     */
    private EmailOutboxEntity buildEntityWithCreatedAt(LocalDateTime createdAt) {
        // EmailOutboxEntity は @Builder 付き。Lombok ビルダーで最小限の値を設定する
        EmailOutboxEntity entity = EmailOutboxEntity.builder()
                .templateKind("VERIFICATION")
                .locale("ja")
                .toAddress(new byte[]{1})
                .toAddressHash(new byte[]{1})
                .sourceDomain("auth")
                .idempotencyKey("test-key-" + createdAt.toString())
                .build();
        // createdAt は @Column(updatable=false) で @PrePersist が設定するため、
        // テスト用にリフレクションで設定する
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        return entity;
    }
}
