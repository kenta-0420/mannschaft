package com.mannschaft.app.performance;

import com.mannschaft.app.performance.entity.PerformanceMonthlySummaryEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceMonthlySummaryRepository;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import com.mannschaft.app.performance.service.PerformanceSummaryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceSummaryService 単体テスト")
class PerformanceSummaryServiceTest {

    @Mock private PerformanceRecordRepository recordRepository;
    @Mock private PerformanceMonthlySummaryRepository summaryRepository;

    @InjectMocks
    private PerformanceSummaryService service;

    @Nested
    @DisplayName("recalculateSummary")
    class RecalculateSummary {
        @Test
        @DisplayName("正常系: 記録ありでサマリーが更新される")
        void 再計算_記録あり_サマリー更新() {
            PerformanceRecordEntity record = PerformanceRecordEntity.builder()
                    .metricId(1L).userId(100L).value(BigDecimal.TEN)
                    .recordedDate(LocalDate.of(2026, 3, 15)).build();
            given(recordRepository.findByMetricIdAndUserIdAndYearMonth(1L, 100L, "2026-03"))
                    .willReturn(List.of(record));
            given(summaryRepository.findByMetricIdAndUserIdAndYearMonth(1L, 100L, "2026-03"))
                    .willReturn(Optional.empty());
            given(summaryRepository.save(any())).willReturn(
                    PerformanceMonthlySummaryEntity.builder().metricId(1L).userId(100L).yearMonth("2026-03").build());

            service.recalculateSummary(1L, 100L, LocalDate.of(2026, 3, 15));
            verify(summaryRepository).save(any());
        }

        @Test
        @DisplayName("正常系: 記録なしで既存サマリーが削除される")
        void 再計算_記録なし_サマリー削除() {
            given(recordRepository.findByMetricIdAndUserIdAndYearMonth(1L, 100L, "2026-03"))
                    .willReturn(List.of());
            PerformanceMonthlySummaryEntity existing = PerformanceMonthlySummaryEntity.builder()
                    .metricId(1L).userId(100L).yearMonth("2026-03").build();
            given(summaryRepository.findByMetricIdAndUserIdAndYearMonth(1L, 100L, "2026-03"))
                    .willReturn(Optional.of(existing));

            service.recalculateSummary(1L, 100L, LocalDate.of(2026, 3, 15));
            verify(summaryRepository).delete(existing);
        }
    }
}
