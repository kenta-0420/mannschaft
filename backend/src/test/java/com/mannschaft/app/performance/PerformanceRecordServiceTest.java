package com.mannschaft.app.performance;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.dto.CreateRecordRequest;
import com.mannschaft.app.performance.dto.RecordResponse;
import com.mannschaft.app.performance.dto.SelfRecordRequest;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceRecordService 単体テスト")
class PerformanceRecordServiceTest {

    @Mock private PerformanceRecordRepository recordRepository;
    @Mock private PerformanceMetricService metricService;
    @Mock private PerformanceSummaryService summaryService;
    @Mock private PerformanceMapper performanceMapper;

    @InjectMocks
    private PerformanceRecordService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long METRIC_ID = 10L;

    private PerformanceMetricEntity createMetric(MetricDataType dataType) {
        return PerformanceMetricEntity.builder()
                .teamId(TEAM_ID).name("テスト").unit("cm")
                .dataType(dataType).aggregationType(AggregationType.SUM)
                .isSelfRecordable(true).build();
    }

    @Nested
    @DisplayName("createRecord")
    class CreateRecord {
        @Test
        @DisplayName("正常系: 記録が作成されサマリーが再計算される")
        void 作成_正常_保存() {
            PerformanceMetricEntity metric = createMetric(MetricDataType.DECIMAL);
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            CreateRecordRequest request = new CreateRecordRequest(
                    METRIC_ID, USER_ID, LocalDate.now(), BigDecimal.valueOf(170.5), null, null);
            PerformanceRecordEntity saved = PerformanceRecordEntity.builder()
                    .metricId(METRIC_ID).userId(USER_ID).value(BigDecimal.valueOf(170.5))
                    .recordedDate(LocalDate.now()).source(RecordSource.ADMIN).build();
            given(recordRepository.save(any())).willReturn(saved);
            given(performanceMapper.toRecordResponse(any(), any(), any())).willReturn(new RecordResponse(
                    1L, METRIC_ID, "テスト", USER_ID, null, null, LocalDate.now(),
                    BigDecimal.valueOf(170.5), "cm", null, "ADMIN", USER_ID, null, null));

            RecordResponse result = service.createRecord(TEAM_ID, USER_ID, request);
            assertThat(result).isNotNull();
            verify(summaryService).recalculateSummary(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("validateValue")
    class ValidateValue {
        @Test
        @DisplayName("異常系: INTEGER型に小数値でPERF_008例外")
        void バリデーション_整数型_小数値_例外() {
            PerformanceMetricEntity metric = createMetric(MetricDataType.INTEGER);
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            CreateRecordRequest request = new CreateRecordRequest(
                    METRIC_ID, USER_ID, LocalDate.now(), BigDecimal.valueOf(10.5), null, null);

            assertThatThrownBy(() -> service.createRecord(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_008"));
        }

        @Test
        @DisplayName("異常系: 範囲外の値でPERF_009例外")
        void バリデーション_範囲外_例外() {
            PerformanceMetricEntity metric = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("テスト").unit("cm")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.SUM)
                    .minValue(BigDecimal.ZERO).maxValue(BigDecimal.valueOf(100))
                    .isSelfRecordable(true).build();
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            CreateRecordRequest request = new CreateRecordRequest(
                    METRIC_ID, USER_ID, LocalDate.now(), BigDecimal.valueOf(200), null, null);

            assertThatThrownBy(() -> service.createRecord(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_009"));
        }
    }

    @Nested
    @DisplayName("createSelfRecord")
    class CreateSelfRecord {
        @Test
        @DisplayName("異常系: 自己記録不可でPERF_006例外")
        void 自己記録_不可_例外() {
            PerformanceMetricEntity metric = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("テスト").unit("cm")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.SUM)
                    .isSelfRecordable(false).build();
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            SelfRecordRequest request = new SelfRecordRequest(METRIC_ID, LocalDate.now(), BigDecimal.TEN, null);

            assertThatThrownBy(() -> service.createSelfRecord(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_006"));
        }
    }

    @Nested
    @DisplayName("deleteRecord")
    class DeleteRecord {
        @Test
        @DisplayName("異常系: 記録不在でPERF_002例外")
        void 削除_不在_例外() {
            given(recordRepository.findById(99L)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteRecord(TEAM_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_002"));
        }
    }
}
