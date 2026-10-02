package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * F09.18 Phase 18-e: メール outbox 監視メトリクス (設計書 §10)。
 *
 * <p>Gauge × 5 + Timer × 1 を MeterRegistry に登録する。
 * Prometheus エンドポイント (/actuator/prometheus) 経由で F10.5 Grafana に公開する。</p>
 *
 * <ul>
 *   <li>§10-1: {@code email_outbox.delivery_success_rate} — 直近 24h の成功率</li>
 *   <li>§10-2: {@code email_outbox.avg_retry_count} — SENT 行の平均リトライ数</li>
 *   <li>§10-3: {@code email_outbox.queue_depth_pending} — PENDING キュー深さ</li>
 *   <li>§10-4: {@code email_outbox.queue_depth_dead_letter} — DEAD_LETTER キュー深さ</li>
 *   <li>§10-5: {@code email_outbox.oldest_pending_age_seconds} — 最古 PENDING の経過秒</li>
 *   <li>§10-6: {@code email_outbox.send_duration_seconds} — SES 送信 1 件あたりの所要時間 (Timer)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EmailOutboxMicrometerMetrics {

    private final MeterRegistry meterRegistry;
    private final EmailOutboxRepository repository;

    @PostConstruct
    void registerGauges() {
        // §10-3: PENDING キュー深さ
        Gauge.builder("email_outbox.queue_depth_pending",
                        repository, r -> r.countByStatus(EmailOutboxStatus.PENDING.name()))
                .description("status=PENDING の件数")
                .register(meterRegistry);

        // §10-4: DEAD_LETTER キュー深さ
        Gauge.builder("email_outbox.queue_depth_dead_letter",
                        repository, r -> r.countByStatus(EmailOutboxStatus.DEAD_LETTER.name()))
                .description("status=DEAD_LETTER の件数")
                .register(meterRegistry);

        // §10-5: 最古 PENDING の経過秒
        Gauge.builder("email_outbox.oldest_pending_age_seconds",
                        repository, r -> r
                                .findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name())
                                .map(e -> (double) Duration.between(
                                        e.getCreatedAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant(),
                                        Instant.now()).getSeconds())
                                .orElse(0.0))
                .description("最古 PENDING エントリの経過秒。PENDING ゼロなら 0")
                .register(meterRegistry);

        // §10-1: 直近 24h 成功率
        Gauge.builder("email_outbox.delivery_success_rate", this, EmailOutboxMicrometerMetrics::calcSuccessRate)
                .description("直近 24h の SENT / (SENT + DEAD_LETTER)。実績ゼロなら 1.0")
                .register(meterRegistry);

        // §10-2: SENT 行の平均リトライ数
        Gauge.builder("email_outbox.avg_retry_count",
                        repository, r -> {
                            Double avg = r.findAvgRetryCountOfSent();
                            return avg != null ? avg : 0.0;
                        })
                .description("SENT 行の平均 retry_count")
                .register(meterRegistry);
    }

    /**
     * §10-6: SES 送信 1 件あたりの所要時間を記録する。
     * EmailOutboxServiceImpl.processOne() から SES 呼び出し成功後に呼ぶ。
     *
     * @param duration     SES 送信にかかった時間
     * @param templateKind テンプレート種別 (tag に使用)
     */
    public void recordSendDuration(Duration duration, String templateKind) {
        meterRegistry.timer("email_outbox.send_duration_seconds",
                        "template_kind", templateKind != null ? templateKind : "unknown")
                .record(duration);
    }

    private double calcSuccessRate() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long sent = repository.countByStatusSince(EmailOutboxStatus.SENT.name(), since);
        long dead = repository.countByStatusSince(EmailOutboxStatus.DEAD_LETTER.name(), since);
        long total = sent + dead;
        // 実績ゼロは「障害なし」とみなして 1.0 を返す（0.0 誤検知を防ぐ）
        return total > 0 ? (double) sent / total : 1.0;
    }
}
