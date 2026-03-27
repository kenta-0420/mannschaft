package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.dto.MemberPerformanceResponse;
import com.mannschaft.app.performance.dto.TeamStatsResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PerformanceStatsService} の追加単体テスト。
 * AVG/MAX/MIN/LATEST aggregation タイプのカバレッジを追加する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceStatsService 追加単体テスト")
class PerformanceStatsServiceAdditionalTest {

    @Mock
    private PerformanceRecordRepository recordRepository;

    @Mock
    private PerformanceMetricService metricService;

    @InjectMocks
    private PerformanceStatsService performanceStatsService;

    private static final Long TEAM_ID = 1L;
    private static final Long METRIC_ID = 100L;
    private static final Long USER_ID_1 = 10L;
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 3, 31);

    private PerformanceMetricEntity createMetric(AggregationType aggType, BigDecimal targetValue) {
        PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                .teamId(TEAM_ID)
                .name("テスト指標")
                .unit("km")
                .aggregationType(aggType)
                .targetValue(targetValue)
                .build();
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, METRIC_ID);
        } catch (Exception ignored) {}
        return entity;
    }

    private PerformanceRecordEntity createRecord(BigDecimal value, LocalDate date) {
        return PerformanceRecordEntity.builder()
                .metricId(METRIC_ID)
                .userId(USER_ID_1)
                .recordedDate(date)
                .value(value)
                .build();
    }

    // ========================================
    // getTeamStats - 各AggregationType
    // ========================================

    @Nested
    @DisplayName("getTeamStats AggregationType別")
    class GetTeamStatsAggregationType {

        @Test
        @DisplayName("正常系: AVG集計タイプで達成率が計算される")
        void getTeamStats_AVG集計_達成率計算() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.AVG, new BigDecimal("50"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("40"), DATE_FROM),
                    createRecord(new BigDecimal("60"), DATE_FROM.plusDays(1))
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            TeamStatsResponse.MetricStats stats = response.getMetrics().get(0);
            assertThat(stats.getAchievementRate()).isNotNull();
        }

        @Test
        @DisplayName("正常系: MAX集計タイプで達成率が計算される")
        void getTeamStats_MAX集計_達成率計算() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.MAX, new BigDecimal("100"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("70"), DATE_FROM),
                    createRecord(new BigDecimal("80"), DATE_FROM.plusDays(1))
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            assertThat(response.getMetrics().get(0).getAchievementRate()).isNotNull();
        }

        @Test
        @DisplayName("正常系: MIN集計タイプで達成率が計算される")
        void getTeamStats_MIN集計_達成率計算() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.MIN, new BigDecimal("30"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("35"), DATE_FROM),
                    createRecord(new BigDecimal("25"), DATE_FROM.plusDays(1))
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            assertThat(response.getMetrics().get(0).getAchievementRate()).isNotNull();
        }

        @Test
        @DisplayName("正常系: LATEST集計タイプで達成率が計算される")
        void getTeamStats_LATEST集計_達成率計算() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.LATEST, new BigDecimal("50"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("45"), LocalDate.of(2026, 1, 10)),
                    createRecord(new BigDecimal("55"), LocalDate.of(2026, 2, 10))
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            assertThat(response.getMetrics().get(0).getAchievementRate()).isNotNull();
        }

        @Test
        @DisplayName("正常系: targetValueがゼロの場合は達成率がnull")
        void getTeamStats_targetValueゼロ_達成率null() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.SUM, BigDecimal.ZERO);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("10"), DATE_FROM)
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics().get(0).getAchievementRate()).isNull();
        }
    }

    // ========================================
    // getMemberPerformance - MIN aggregation type
    // ========================================

    @Nested
    @DisplayName("getMemberPerformance MIN集計タイプ")
    class GetMemberPerformanceMIN {

        @Test
        @DisplayName("正常系: MIN集計でpersonalBestが最小値レコードになる")
        void getMemberPerformance_MIN集計_personalBest最小値() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.MIN, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("100"), LocalDate.of(2026, 1, 15)),
                    createRecord(new BigDecimal("50"), LocalDate.of(2026, 2, 15))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(eq(USER_ID_1), any(), any(), any()))
                    .willReturn(records);

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            MemberPerformanceResponse.MetricDetail detail = response.getMetrics().get(0);
            assertThat(detail.getPersonalBest()).isNotNull();
            assertThat(detail.getPersonalBest().getValue()).isEqualByComparingTo(new BigDecimal("50"));
        }

        @Test
        @DisplayName("正常系: MAX集計でpersonalBestが最大値レコードになる")
        void getMemberPerformance_MAX集計_personalBest最大値() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.MAX, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(new BigDecimal("30"), LocalDate.of(2026, 1, 15)),
                    createRecord(new BigDecimal("90"), LocalDate.of(2026, 2, 15))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(eq(USER_ID_1), any(), any(), any()))
                    .willReturn(records);

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            MemberPerformanceResponse.MetricDetail detail = response.getMetrics().get(0);
            assertThat(detail.getPersonalBest()).isNotNull();
            assertThat(detail.getPersonalBest().getValue()).isEqualByComparingTo(new BigDecimal("90"));
        }

        @Test
        @DisplayName("正常系: previousValueがゼロの場合changeRateがnull")
        void getMemberPerformance_previousValueゼロ_changeRateNull() {
            // Given
            PerformanceMetricEntity metric = createMetric(AggregationType.SUM, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(BigDecimal.ZERO, LocalDate.of(2026, 1, 15)),
                    createRecord(new BigDecimal("50"), LocalDate.of(2026, 2, 15))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(eq(USER_ID_1), any(), any(), any()))
                    .willReturn(records);

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            // previousValue=0なのでchangeRateはnull
            assertThat(response.getMetrics().get(0).getChangeRate()).isNull();
        }
    }
}
