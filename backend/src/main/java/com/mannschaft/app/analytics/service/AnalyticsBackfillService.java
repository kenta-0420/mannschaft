package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.BackfillTarget;
import com.mannschaft.app.analytics.dto.BackfillJobResponse;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 過去データの再集計（バックフィル）。非同期実行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsBackfillService {

    private final DailyAggregationBatchService dailyBatch;
    private final MonthlyCohortBatchService cohortBatch;
    private static final long MAX_BACKFILL_DAYS = 183; // 6ヶ月

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * バックフィルを開始する。非同期実行。
     */
    public BackfillJobResponse startBackfill(BackfillRequest request) {
        if (request.getFrom().isAfter(request.getTo())) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_005);
        }
        long days = ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1;
        if (days > MAX_BACKFILL_DAYS) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_003);
        }

        String jobId = "backfill-" + LocalDate.now().toString().replace("-", "") + "-"
                + LocalTime.now().toString().replace(":", "").substring(0, 6);

        executeAsync(request, jobId);

        return new BackfillJobResponse(
                jobId, "RUNNING", request.getFrom(), request.getTo(),
                request.getTargets().stream().map(Enum::name).toList(),
                LocalDateTime.now()
        );
    }

    @Async
    protected void executeAsync(BackfillRequest request, String jobId) {
        try {
            log.info("バックフィル開始: jobId={}, from={}, to={}, targets={}",
                    jobId, request.getFrom(), request.getTo(), request.getTargets());

            LocalDate current = request.getFrom();
            int processedDays = 0;
            long totalDays = ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1;

            while (!current.isAfter(request.getTo())) {
                try {
                    if (request.getTargets().stream().anyMatch(t -> t != BackfillTarget.COHORTS)) {
                        dailyBatch.aggregateForDate(current);
                    }
                    processedDays++;
                    if (processedDays % 10 == 0) {
                        log.info("バックフィル進捗: {}/{} 日完了", processedDays, totalDays);
                    }
                } catch (Exception e) {
                    log.warn("バックフィル: date={} でエラー発生、スキップ", current, e);
                }
                current = current.plusDays(1);
            }

            // COHORTS が含まれる場合はコホート再計算
            if (request.getTargets().contains(BackfillTarget.COHORTS)) {
                cohortBatch.recalculateForMonth(request.getTo().withDayOfMonth(1));
            }

            log.info("バックフィル完了: jobId={}", jobId);
            // TODO: SYSTEM_ADMIN プッシュ通知
        } finally {
            running.set(false);
        }
    }
}
