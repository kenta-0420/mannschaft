package com.mannschaft.app.mail.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F09.18 メール配信 outbox の送信ワーカー (設計書 §7.2)。
 *
 * <p>10 秒間隔で {@code findReadyForSending(50)} を呼び、各行を
 * {@link EmailOutboxService#processOne} で個別 TX (REQUIRES_NEW) で処理する。
 * ShedLock により複数 pod 起動時も同時実行されない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxWorker {

    /** ポーリングごとの最大処理件数 (設計書 §7.2)。 */
    private static final int BATCH_SIZE = 50;

    private final EmailOutboxRepository repository;
    private final EmailOutboxService outboxService;
    private final MeterRegistry meterRegistry;

    /**
     * 10 秒間隔で PENDING 行を取得し処理する。
     *
     * <p>{@code @Scheduled(fixedDelay)} と {@code @SchedulerLock} を併用。
     * {@code lockAtMostFor=PT2M} は 1 バッチが極端に長引いた場合の保険
     * (1 件あたり最大 30 秒 × 50 件 = 25 分まではかからないが、安全側に短く設定)。</p>
     */
    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(
            name = "emailOutboxWorker",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void poll() {
        LockAssert.assertLocked();
        List<EmailOutboxEntity> batch = repository.findReadyForSending(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("EmailOutboxWorker picked {} rows", batch.size());
        for (EmailOutboxEntity row : batch) {
            try {
                outboxService.processOne(row.getId());
            } catch (Exception ex) {
                meterRegistry.counter("email_outbox.worker.unexpected_error").increment();
                log.error("EmailOutboxWorker unexpected error for {}", row.getId(), ex);
            }
        }
    }
}
