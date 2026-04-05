package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * パフォーマンス月次サマリー日次バッチサービス。
 * 毎朝3:00に前日の記録を集計し、月次サマリーを UPSERT する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceBatchService {

    private final PerformanceRecordRepository recordRepository;
    private final PerformanceSummaryService summaryService;

    /**
     * 日次バッチ: 前日の記録を月次サマリーに反映する。
     * 毎朝3:00に実行。
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void aggregateDailySummaries() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("パフォーマンス月次サマリーバッチ開始: 対象日={}", yesterday);

        List<Object[]> metricUserPairs = recordRepository.findDistinctMetricUserByDate(yesterday);

        int count = 0;
        for (Object[] pair : metricUserPairs) {
            Long metricId = ((Number) pair[0]).longValue();
            Long userId = ((Number) pair[1]).longValue();
            summaryService.recalculateSummary(metricId, userId, yesterday);
            count++;
        }

        log.info("パフォーマンス月次サマリーバッチ完了: {}件のサマリーを更新", count);
    }
}
