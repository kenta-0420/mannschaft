package com.mannschaft.app.performance;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.dto.BulkRecordRequest;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.ScheduleBulkRecordRequest;
import com.mannschaft.app.performance.dto.UpdateRecordRequest;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import com.mannschaft.app.performance.service.PerformanceMetricService;
import com.mannschaft.app.performance.service.PerformanceRecordService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PerformanceRecordService} の追加単体テスト。
 * updateRecord / createBulkRecords / createScheduleBulkRecords を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceRecordService 追加単体テスト")
class PerformanceRecordServiceAdditionalTest {

    @Mock
    private PerformanceRecordRepository recordRepository;

    @Mock
    private PerformanceMetricService metricService;

    @Mock
    private PerformanceSummaryService summaryService;

    @Mock
    private PerformanceMapper performanceMapper;

    @InjectMocks
    private PerformanceRecordService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long METRIC_ID = 10L;

    private PerformanceMetricEntity createDecimalMetric() {
        return PerformanceMetricEntity.builder()
                .teamId(TEAM_ID)
                .name("テスト指標")
                .unit("km")
                .dataType(MetricDataType.DECIMAL)
                .aggregationType(AggregationType.SUM)
                .isSelfRecordable(true)
                .build();
    }

    private PerformanceRecordEntity createRecord(Long id) {
        PerformanceRecordEntity entity = PerformanceRecordEntity.builder()
                .metricId(METRIC_ID)
                .userId(USER_ID)
                .value(BigDecimal.valueOf(50))
                .recordedDate(LocalDate.of(2026, 2, 1))
                .source(RecordSource.ADMIN)
                .build();
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {}
        return entity;
    }

    // ========================================
    // updateRecord
    // ========================================

    @Nested
    @DisplayName("updateRecord")
    class UpdateRecord {

        @Test
        @DisplayName("正常系: 日付変更なしで旧月サマリーのみ再計算")
        void updateRecord_日付変更なし_サマリー1回再計算() {
            // Given
            PerformanceRecordEntity entity = createRecord(1L);
            PerformanceMetricEntity metric = createDecimalMetric();
            LocalDate sameDate = LocalDate.of(2026, 2, 1);
            UpdateRecordRequest request = new UpdateRecordRequest(
                    BigDecimal.valueOf(60), "更新メモ", sameDate);
            RecordResponse response = new RecordResponse(
                    1L, METRIC_ID, "テスト指標", USER_ID, null, null, sameDate,
                    BigDecimal.valueOf(60), "km", "更新メモ", "ADMIN", USER_ID, null, null);

            given(recordRepository.findById(1L)).willReturn(Optional.of(entity));
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            given(recordRepository.save(any())).willReturn(entity);
            given(performanceMapper.toRecordResponse(any(), any(), any())).willReturn(response);

            // When
            RecordResponse result = service.updateRecord(TEAM_ID, 1L, request);

            // Then
            assertThat(result).isNotNull();
            verify(summaryService, times(1)).recalculateSummary(any(), any(), any());
        }

        @Test
        @DisplayName("正常系: 日付変更ありで旧月と新月の両方が再計算される")
        void updateRecord_日付変更あり_サマリー2回再計算() {
            // Given
            PerformanceRecordEntity entity = createRecord(1L);
            PerformanceMetricEntity metric = createDecimalMetric();
            LocalDate newDate = LocalDate.of(2026, 3, 1); // 旧日付は2026-02-01
            UpdateRecordRequest request = new UpdateRecordRequest(
                    BigDecimal.valueOf(60), null, newDate);
            RecordResponse response = new RecordResponse(
                    1L, METRIC_ID, "テスト指標", USER_ID, null, null, newDate,
                    BigDecimal.valueOf(60), "km", null, "ADMIN", USER_ID, null, null);

            given(recordRepository.findById(1L)).willReturn(Optional.of(entity));
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            given(recordRepository.save(any())).willReturn(entity);
            given(performanceMapper.toRecordResponse(any(), any(), any())).willReturn(response);

            // When
            RecordResponse result = service.updateRecord(TEAM_ID, 1L, request);

            // Then
            assertThat(result).isNotNull();
            verify(summaryService, times(2)).recalculateSummary(any(), any(), any());
        }

        @Test
        @DisplayName("異常系: 記録不在でPERF_002例外")
        void updateRecord_不在_例外() {
            // Given
            given(recordRepository.findById(99L)).willReturn(Optional.empty());
            UpdateRecordRequest request = new UpdateRecordRequest(
                    BigDecimal.valueOf(10), null, LocalDate.now());

            // When & Then
            assertThatThrownBy(() -> service.updateRecord(TEAM_ID, 99L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_002"));
        }
    }

    // ========================================
    // createBulkRecords
    // ========================================

    @Nested
    @DisplayName("createBulkRecords")
    class CreateBulkRecords {

        @Test
        @DisplayName("正常系: 一括記録が作成される")
        void createBulkRecords_正常_作成される() {
            // Given
            PerformanceMetricEntity metric = createDecimalMetric();
            BulkRecordRequest.Entry entry1 = new BulkRecordRequest.Entry(USER_ID, METRIC_ID, BigDecimal.valueOf(10));
            BulkRecordRequest.Entry entry2 = new BulkRecordRequest.Entry(200L, METRIC_ID, BigDecimal.valueOf(20));
            BulkRecordRequest request = new BulkRecordRequest(
                    LocalDate.of(2026, 3, 1), "一括メモ", List.of(entry1, entry2));

            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            given(recordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            var result = service.createBulkRecords(TEAM_ID, USER_ID, request);

            // Then
            assertThat(result.getCreatedCount()).isEqualTo(2);
            verify(recordRepository, times(2)).save(any());
            verify(summaryService, times(2)).recalculateSummary(any(), any(), any());
        }

        @Test
        @DisplayName("異常系: 値バリデーション失敗でPERF_007例外")
        void createBulkRecords_バリデーション失敗_例外() {
            // Given
            PerformanceMetricEntity metric = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("テスト").unit("km")
                    .dataType(MetricDataType.INTEGER)
                    .aggregationType(AggregationType.SUM)
                    .isSelfRecordable(true)
                    .build();
            BulkRecordRequest.Entry entry = new BulkRecordRequest.Entry(USER_ID, METRIC_ID, BigDecimal.valueOf(10.5));
            BulkRecordRequest request = new BulkRecordRequest(
                    LocalDate.of(2026, 3, 1), null, List.of(entry));

            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);

            // When & Then
            assertThatThrownBy(() -> service.createBulkRecords(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_007"));
        }
    }

    // ========================================
    // createScheduleBulkRecords
    // ========================================

    @Nested
    @DisplayName("createScheduleBulkRecords")
    class CreateScheduleBulkRecords {

        @Test
        @DisplayName("正常系: スケジュール一括記録が作成される")
        void createScheduleBulkRecords_正常_作成される() {
            // Given
            Long scheduleId = 500L;
            PerformanceMetricEntity metric = createDecimalMetric();
            ScheduleBulkRecordRequest.Entry entry = new ScheduleBulkRecordRequest.Entry(
                    USER_ID, METRIC_ID, BigDecimal.valueOf(15));
            ScheduleBulkRecordRequest request = new ScheduleBulkRecordRequest(null, List.of(entry));

            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            given(recordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            var result = service.createScheduleBulkRecords(TEAM_ID, scheduleId, USER_ID, request);

            // Then
            assertThat(result.getCreatedCount()).isEqualTo(1);
            assertThat(result.getScheduleId()).isEqualTo(scheduleId);
            verify(summaryService, times(1)).recalculateSummary(any(), any(), any());
        }
    }

    // ========================================
    // deleteRecord (正常系)
    // ========================================

    @Nested
    @DisplayName("deleteRecord_正常系")
    class DeleteRecordNormal {

        @Test
        @DisplayName("正常系: 記録が削除されサマリーが再計算される")
        void deleteRecord_正常_削除される() {
            // Given
            PerformanceRecordEntity entity = createRecord(1L);
            PerformanceMetricEntity metric = createDecimalMetric();

            given(recordRepository.findById(1L)).willReturn(Optional.of(entity));
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);

            // When
            service.deleteRecord(TEAM_ID, 1L);

            // Then
            verify(recordRepository).delete(entity);
            verify(summaryService).recalculateSummary(any(), any(), any());
        }
    }
}
