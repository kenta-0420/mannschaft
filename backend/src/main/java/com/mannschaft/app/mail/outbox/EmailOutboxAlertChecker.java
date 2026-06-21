package com.mannschaft.app.mail.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * F09.18 Phase 18-e: メール outbox アラート閾値チェッカー (設計書 §10)。
 *
 * <p>毎分 (fixedDelay=60s) に 4 閾値を評価し、超過時にログ出力する。
 * CRITICAL 判定は log.error で記録し、F10.6 エラー監視 / Prometheus alerting ルールに連携する。
 * SYSTEM_ADMIN プッシュ通知の直接実装は Phase 18-f 以降で F10.5/F10.6 と統合する予定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxAlertChecker {

    /** §10 WARN: PENDING 件数上限 */
    static final long WARN_QUEUE_DEPTH_PENDING = 1_000;
    /** §10 WARN: 最古 PENDING 経過秒上限 */
    static final long WARN_OLDEST_PENDING_SECONDS = 300;
    /** §10 CRITICAL: 成功率下限 */
    static final double CRITICAL_SUCCESS_RATE = 0.95;
    /** §10 CRITICAL: DEAD_LETTER 件数上限 */
    static final long CRITICAL_QUEUE_DEPTH_DEAD_LETTER = 10;

    private final EmailOutboxRepository repository;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void checkAlerts() {
        checkQueueDepthPending();
        checkOldestPendingAge();
        checkSuccessRate();
        checkDeadLetterDepth();
    }

    void checkQueueDepthPending() {
        long pending = repository.countByStatus(EmailOutboxStatus.PENDING.name());
        if (pending > WARN_QUEUE_DEPTH_PENDING) {
            log.warn("[EMAIL_OUTBOX][WARN] PENDING キュー超過: {} 件 (閾値 {})",
                    pending, WARN_QUEUE_DEPTH_PENDING);
        }
    }

    void checkOldestPendingAge() {
        repository.findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name())
                .ifPresent(oldest -> {
                    long ageSeconds = Duration.between(
                            oldest.getCreatedAt(), LocalDateTime.now()).getSeconds();
                    if (ageSeconds > WARN_OLDEST_PENDING_SECONDS) {
                        log.warn("[EMAIL_OUTBOX][WARN] 最古 PENDING が {}秒 滞留中 (閾値 {}秒): id={}",
                                ageSeconds, WARN_OLDEST_PENDING_SECONDS, oldest.getId());
                    }
                });
    }

    void checkSuccessRate() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long sent = repository.countByStatusSince(EmailOutboxStatus.SENT.name(), since);
        long dead = repository.countByStatusSince(EmailOutboxStatus.DEAD_LETTER.name(), since);
        long total = sent + dead;
        if (total == 0) {
            return; // 実績ゼロはチェックしない
        }
        double rate = (double) sent / total;
        if (rate < CRITICAL_SUCCESS_RATE) {
            log.error("[EMAIL_OUTBOX][CRITICAL] 直近24h 成功率が低下: {}% (閾値 {}%)",
                    String.format("%.1f", rate * 100), (int) (CRITICAL_SUCCESS_RATE * 100));
        }
    }

    void checkDeadLetterDepth() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long deadLetter = repository.countByStatusSince(EmailOutboxStatus.DEAD_LETTER.name(), since);
        if (deadLetter > CRITICAL_QUEUE_DEPTH_DEAD_LETTER) {
            log.error("[EMAIL_OUTBOX][CRITICAL] 直近24h の DEAD_LETTER が {} 件 (閾値 {})",
                    deadLetter, CRITICAL_QUEUE_DEPTH_DEAD_LETTER);
        }
    }
}
