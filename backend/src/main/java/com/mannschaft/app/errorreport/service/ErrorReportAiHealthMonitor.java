package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F12.5 Phase 2-F — AI 分析サービスのヘルス監視バッチ。
 *
 * <p>毎時 0 分（JST）に直近 24 時間の {@code error_report_ai_analyses.status = 'FAILED'} 件数を
 * 集計し、{@link #FAILURE_THRESHOLD} 以上になっていれば SYSTEM_ADMIN へ Slack + プッシュ通知を
 * 1 日 1 回まで送信する。</p>
 *
 * <p>連続失敗の主因として想定するのは Claude API キー失効、残高切れ、レート制限超過、
 * ネットワーク到達不可などインフラ側の障害。閾値到達は予算消費とは別の異常シグナルなので
 * {@link ErrorReportAiBudgetMonitorBatch} とは独立に通知する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportAiHealthMonitor {

    /** 24h 以内に FAILED が何件以上で通知するかの閾値。 */
    static final int FAILURE_THRESHOLD = 5;

    private static final String FAILED_STATUS = "FAILED";
    private static final String ALERT_KEY_PREFIX = "error-report:ai-health-alert:";

    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final ErrorReportNotifier notifier;
    private final StringRedisTemplate redisTemplate;

    /**
     * 毎時 0 分（JST）に実行されるエントリポイント。
     */
    @BatchEndpoint(name = "errorreport-ai-health-monitor-hourly", description = "AI 分析の FAILED 件数を毎時集計し閾値超過時に通知する")
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "errorReportAiHealthMonitor",
            lockAtMostFor = "PT2H",
            lockAtLeastFor = "PT30S")
    public void execute() {
        executeAt(LocalDateTime.now());
    }

    /**
     * テスト容易性のために now を引数で受け取れるパッケージプライベート版。
     *
     * @param now 基準時刻
     */
    void executeAt(LocalDateTime now) {
        LocalDateTime since = now.minusHours(24);
        long failureCount =
                aiAnalysisRepository.countByStatusAndCreatedAtAfter(FAILED_STATUS, since);
        if (failureCount < FAILURE_THRESHOLD) {
            return;
        }

        String alertKey = ALERT_KEY_PREFIX + LocalDate.now();
        Boolean isFirstAlert = redisTemplate.opsForValue()
                .setIfAbsent(alertKey, "true", Duration.ofDays(1));
        if (!Boolean.TRUE.equals(isFirstAlert)) {
            log.debug("AI ヘルス劣化通知は本日既に送信済み: failureCount={}", failureCount);
            return;
        }

        notifier.notifyAiHealthDegraded(failureCount);
        log.info("AI ヘルス劣化通知を送信: failureCount={}", failureCount);
    }
}
