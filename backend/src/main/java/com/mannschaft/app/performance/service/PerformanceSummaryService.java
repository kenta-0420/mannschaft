package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.entity.PerformanceMonthlySummaryEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceMonthlySummaryRepository;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * パフォーマンスサマリーサービス。月次サマリーの再計算を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceSummaryService {

    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PerformanceRecordRepository recordRepository;
    private final PerformanceMonthlySummaryRepository summaryRepository;

    /**
     * 指定された指標×ユーザー×日付のサマリーを再計算する。
     *
     * @param metricId 指標ID
     * @param userId   ユーザーID
     * @param date     対象日
     */
    @Transactional
    public void recalculateSummary(Long metricId, Long userId, LocalDate date) {
        String yearMonth = date.format(YEAR_MONTH_FMT);
        List<PerformanceRecordEntity> records = recordRepository.findByMetricIdAndUserIdAndYearMonth(
                metricId, userId, yearMonth);

        Optional<PerformanceMonthlySummaryEntity> existing =
                summaryRepository.findByMetricIdAndUserIdAndYearMonth(metricId, userId, yearMonth);

        if (records.isEmpty()) {
            existing.ifPresent(summaryRepository::delete);
            return;
        }

        BigDecimal sumValue = records.stream().map(PerformanceRecordEntity::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgValue = sumValue.divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP);
        BigDecimal maxValue = records.stream().map(PerformanceRecordEntity::getValue)
                .max(Comparator.naturalOrder()).orElse(null);
        BigDecimal minValue = records.stream().map(PerformanceRecordEntity::getValue)
                .min(Comparator.naturalOrder()).orElse(null);
        BigDecimal latestValue = records.stream()
                .max(Comparator.comparing(PerformanceRecordEntity::getRecordedDate))
                .map(PerformanceRecordEntity::getValue)
                .orElse(null);

        PerformanceMonthlySummaryEntity summary = existing.orElseGet(() ->
                PerformanceMonthlySummaryEntity.builder()
                        .metricId(metricId)
                        .userId(userId)
                        .yearMonth(yearMonth)
                        .build());

        summary.updateSummary(sumValue, avgValue, maxValue, minValue, latestValue, records.size());
        summaryRepository.save(summary);
    }
}
