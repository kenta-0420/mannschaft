package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.common.batch.BatchEndpointExempt;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     *
     * <p><b>バッチ実行履歴基盤（{@code @BatchEndpoint}）へ登録しない理由</b>:
     * 10 秒間隔＝日次 8,640 回の起動であり、1 回ごとに実行履歴を書くと
     * 履歴テーブルが「送信対象 0 件」の記録で埋まり、日次・月次バッチの記録が埋没する。
     * 本ワーカーの可観測性は {@link MeterRegistry} のメトリクス（送信件数・失敗件数）で
     * 担保しているため、実行履歴は不要である。</p>
     */
    @BatchEndpointExempt("10 秒間隔（日次 8,640 回）の高頻度ワーカーであり、"
        + "実行履歴を書くと日次・月次バッチの記録が埋没する。可観測性は MeterRegistry のメトリクスで担保")
    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(
            name = "emailOutboxWorker",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    // 検分 P1: 防御策。poll() 全体を 1 トランザクションで包んでしまうと processOne の
    // REQUIRES_NEW が機能しない（外側 TX が rollback すると全件巻き戻る）ため、
    // 外側 TX を明示的に張らない契約を NEVER で固定する。
    @Transactional(propagation = Propagation.NEVER)
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
