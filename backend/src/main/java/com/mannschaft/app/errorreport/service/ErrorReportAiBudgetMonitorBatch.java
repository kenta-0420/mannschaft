package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * F12.5 Phase 2-C — AI 月次予算監視バッチ。
 *
 * <p>毎時 0 分（JST）に当月の累計支出をチェックし、
 * 80% / 100% 到達時に 1 度だけ Slack + SYSTEM_ADMIN 通知を送信する。
 * 通知済みフラグは Valkey の HASH に月単位で保持し TTL 35 日でリセットされる。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportAiBudgetMonitorBatch {

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMM");
    private static final String ALERT_KEY_PREFIX = "error-report:ai-budget-alert:";
    private static final String FLAG_ALERT_80 = "alert80";
    private static final String FLAG_ALERT_100 = "alert100";
    private static final String FLAG_TRUE = "true";
    private static final double THRESHOLD_80 = 0.8;
    private static final double THRESHOLD_100 = 1.0;

    private final ErrorReportAiBudgetService budgetService;
    private final ErrorReportNotifier notifier;
    private final ErrorReportProperties props;
    private final StringRedisTemplate redisTemplate;

    /**
     * 毎時 0 分（JST）に実行。
     */
    @BatchEndpoint(name = "errorreport-ai-budget-monitor-hourly", description = "AI 月次予算の 80% / 100% 到達を毎時監視して通知する")
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "errorReportAiBudgetMonitor",
            lockAtMostFor = "PT2H",
            lockAtLeastFor = "PT30S")
    public void execute() {
        if (!props.getAi().isEnabled()) {
            return;
        }
        long expense = budgetService.currentMonthlyExpense();
        int budget = props.getAi().getMonthlyBudgetJpy();
        if (budget <= 0) {
            return;
        }
        double ratio = (double) expense / budget;

        String alertKey = ALERT_KEY_PREFIX + LocalDate.now().format(MONTH_FORMAT);

        if (ratio >= THRESHOLD_100
                && !FLAG_TRUE.equals(redisTemplate.opsForHash().get(alertKey, FLAG_ALERT_100))) {
            notifier.notifyBudgetExceeded(budget, expense);
            redisTemplate.opsForHash().put(alertKey, FLAG_ALERT_100, FLAG_TRUE);
            redisTemplate.expire(alertKey, Duration.ofDays(35));
            log.info("AI 月次予算 100% 通知を送信: budget=¥{}, expense=¥{}", budget, expense);
        } else if (ratio >= THRESHOLD_80
                && !FLAG_TRUE.equals(redisTemplate.opsForHash().get(alertKey, FLAG_ALERT_80))) {
            notifier.notifyBudgetWarning(budget, expense);
            redisTemplate.opsForHash().put(alertKey, FLAG_ALERT_80, FLAG_TRUE);
            redisTemplate.expire(alertKey, Duration.ofDays(35));
            log.info("AI 月次予算 80% 通知を送信: budget=¥{}, expense=¥{}", budget, expense);
        }
    }
}
