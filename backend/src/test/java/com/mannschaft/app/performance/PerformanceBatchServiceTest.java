package com.mannschaft.app.performance;

import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import com.mannschaft.app.performance.service.PerformanceBatchService;
import com.mannschaft.app.performance.service.PerformanceSummaryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceBatchService 単体テスト")
class PerformanceBatchServiceTest {

    @Mock private PerformanceRecordRepository recordRepository;
    @Mock private PerformanceSummaryService summaryService;

    @InjectMocks
    private PerformanceBatchService service;

    @Nested
    @DisplayName("aggregateDailySummaries")
    class AggregateDailySummaries {
        @Test
        @DisplayName("正常系: 前日の記録ペアからサマリーが再計算される")
        void バッチ_正常_サマリー再計算() {
            Object[] pair1 = new Object[]{1L, 100L};
            Object[] pair2 = new Object[]{2L, 200L};
            given(recordRepository.findDistinctMetricUserByDate(any(LocalDate.class)))
                    .willReturn(List.of(pair1, pair2));

            service.aggregateDailySummaries();

            verify(summaryService, times(2)).recalculateSummary(any(), any(), any());
        }

        @Test
        @DisplayName("正常系: 記録なしで処理がスキップされる")
        void バッチ_記録なし_スキップ() {
            given(recordRepository.findDistinctMetricUserByDate(any(LocalDate.class)))
                    .willReturn(List.of());

            service.aggregateDailySummaries();

            verify(summaryService, times(0)).recalculateSummary(any(), any(), any());
        }
    }
}
