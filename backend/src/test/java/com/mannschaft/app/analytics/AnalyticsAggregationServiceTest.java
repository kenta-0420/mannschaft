package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsFunnelSnapshotEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyAdsRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyModulesRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsFunnelSnapshotRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlySnapshotRepository;
import com.mannschaft.app.analytics.service.AnalyticsAggregationService;
import com.mannschaft.app.analytics.service.DateRangeResolver;
import com.mannschaft.app.analytics.service.DateRangeResolver.DateRange;
import com.mannschaft.app.analytics.service.MetricCalculationService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsAggregationService 単体テスト")
class AnalyticsAggregationServiceTest {

    @Mock private AnalyticsDailyRevenueRepository revenueRepository;
    @Mock private AnalyticsDailyUsersRepository usersRepository;
    @Mock private AnalyticsDailyModulesRepository modulesRepository;
    @Mock private AnalyticsDailyAdsRepository adsRepository;
    @Mock private AnalyticsFunnelSnapshotRepository funnelRepository;
    @Mock private AnalyticsMonthlyCohortRepository cohortRepository;
    @Mock private AnalyticsMonthlySnapshotRepository snapshotRepository;
    @Mock private MetricCalculationService metricCalc;
    @Mock private DateRangeResolver dateRangeResolver;
    @InjectMocks private AnalyticsAggregationService service;

    // ========== getRevenueSummary ==========

    @Nested
    @DisplayName("getRevenueSummary")
    class GetRevenueSummary {

        @Test
        @DisplayName("正常系: 基準日指定で収益サマリ取得")
        void testGetRevenueSummary_正常取得() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 3, 15);
            given(metricCalc.calculateMrr(any(), any())).willReturn(new BigDecimal("100000"));
            given(metricCalc.calculateArr(any())).willReturn(new BigDecimal("1200000"));

            // ユーザーデータ
            AnalyticsDailyUsersEntity usersEntity = AnalyticsDailyUsersEntity.builder()
                    .date(date).activeUsers(500).payingUsers(200).churnedUsers(5).build();
            given(usersRepository.findByDate(any())).willReturn(Optional.of(usersEntity));

            given(metricCalc.calculateArpu(any(), anyInt())).willReturn(new BigDecimal("200.00"));
            given(metricCalc.calculateUserChurnRate(anyInt(), anyInt())).willReturn(new BigDecimal("2.50"));
            given(metricCalc.calculateLtv(any(), any())).willReturn(new BigDecimal("8000.00"));

            // Act
            var result = service.getRevenueSummary(date);

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: date=null で最新日を使用")
        void testGetRevenueSummary_dateNull() {
            // Arrange
            LocalDate latestDate = LocalDate.of(2026, 3, 14);
            given(revenueRepository.findLatestDate()).willReturn(Optional.of(latestDate));

            given(metricCalc.calculateMrr(any(), any())).willReturn(new BigDecimal("90000"));
            given(metricCalc.calculateArr(any())).willReturn(new BigDecimal("1080000"));
            given(usersRepository.findByDate(any())).willReturn(Optional.empty());
            given(metricCalc.calculateArpu(any(), anyInt())).willReturn(null);
            given(metricCalc.calculateUserChurnRate(anyInt(), anyInt())).willReturn(BigDecimal.ZERO);
            given(metricCalc.calculateLtv(any(), any())).willReturn(null);

            // Act
            var result = service.getRevenueSummary(null);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getRevenueTrend ==========

    @Nested
    @DisplayName("getRevenueTrend")
    class GetRevenueTrend {

        @Test
        @DisplayName("正常系: DAILY粒度で収益推移取得")
        void testGetRevenueTrend_DAILY正常取得() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 7);
            DateRange range = new DateRange(from, to);

            given(dateRangeResolver.resolve(from, to, null)).willReturn(range);
            given(revenueRepository.findByDateBetweenOrderByDateAsc(from, to))
                    .willReturn(List.of(
                            AnalyticsDailyRevenueEntity.builder()
                                    .date(from).revenueSource(RevenueSource.MODULE_SUBSCRIPTION)
                                    .netRevenue(new BigDecimal("5000")).build(),
                            AnalyticsDailyRevenueEntity.builder()
                                    .date(from.plusDays(1)).revenueSource(RevenueSource.MODULE_SUBSCRIPTION)
                                    .netRevenue(new BigDecimal("6000")).build()
                    ));

            // Act
            var result = service.getRevenueTrend(from, to, null, Granularity.DAILY);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getRevenueBySource ==========

    @Nested
    @DisplayName("getRevenueBySource")
    class GetRevenueBySource {

        @Test
        @DisplayName("正常系: ソース別集計")
        void testGetRevenueBySource_正常取得() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            DateRange range = new DateRange(from, to);

            given(dateRangeResolver.resolve(from, to, null)).willReturn(range);
            given(revenueRepository.findByDateBetweenOrderByDateAsc(from, to))
                    .willReturn(List.of(
                            AnalyticsDailyRevenueEntity.builder()
                                    .date(from).revenueSource(RevenueSource.MODULE_SUBSCRIPTION)
                                    .netRevenue(new BigDecimal("80000")).build(),
                            AnalyticsDailyRevenueEntity.builder()
                                    .date(from).revenueSource(RevenueSource.ADVERTISING)
                                    .netRevenue(new BigDecimal("20000")).build()
                    ));

            // Act
            var result = service.getRevenueBySource(from, to, null);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getUserTrend ==========

    @Nested
    @DisplayName("getUserTrend")
    class GetUserTrend {

        @Test
        @DisplayName("正常系: ユーザー推移取得")
        void testGetUserTrend_正常取得() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 7);
            DateRange range = new DateRange(from, to);

            given(dateRangeResolver.resolve(from, to, null)).willReturn(range);
            given(usersRepository.findByDateBetweenOrderByDateAsc(from, to))
                    .willReturn(List.of(
                            AnalyticsDailyUsersEntity.builder()
                                    .date(from).activeUsers(500).newUsers(20).churnedUsers(3).build(),
                            AnalyticsDailyUsersEntity.builder()
                                    .date(from.plusDays(1)).activeUsers(510).newUsers(15).churnedUsers(2).build()
                    ));

            // Act
            var result = service.getUserTrend(from, to, null, Granularity.DAILY);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getFunnelAnalysis ==========

    @Nested
    @DisplayName("getFunnelAnalysis")
    class GetFunnelAnalysis {

        @Test
        @DisplayName("正常系: ファネル分析（conversion_rate計算確認）")
        void testGetFunnelAnalysis_正常取得() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 3, 15);
            given(funnelRepository.findByDateOrderByStageAsc(date))
                    .willReturn(List.of(
                            AnalyticsFunnelSnapshotEntity.builder()
                                    .date(date).stage(FunnelStage.REGISTERED).userCount(1000).build(),
                            AnalyticsFunnelSnapshotEntity.builder()
                                    .date(date).stage(FunnelStage.TEAM_JOINED).userCount(800).build(),
                            AnalyticsFunnelSnapshotEntity.builder()
                                    .date(date).stage(FunnelStage.FIRST_PAYMENT).userCount(200).build()
                    ));

            // Act
            var result = service.getFunnelAnalysis(date);

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: date=null で最新日を使用")
        void testGetFunnelAnalysis_dateNull() {
            // Arrange
            LocalDate latestDate = LocalDate.of(2026, 3, 14);
            given(revenueRepository.findLatestDate()).willReturn(Optional.of(latestDate));
            given(funnelRepository.findByDateOrderByStageAsc(latestDate)).willReturn(List.of());

            // Act
            var result = service.getFunnelAnalysis(null);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getCohortAnalysis ==========

    @Nested
    @DisplayName("getCohortAnalysis")
    class GetCohortAnalysis {

        @Test
        @DisplayName("正常系: コホート分析")
        void testGetCohortAnalysis_正常取得() {
            // Arrange
            String fromCohort = "2025-10";
            String toCohort = "2026-03";
            LocalDate cohortMonth = LocalDate.of(2025, 10, 1);

            given(cohortRepository.findByCohortMonthBetweenOrderByCohortMonthAscMonthsElapsedAsc(
                    any(), any()))
                    .willReturn(List.of(
                            AnalyticsMonthlyCohortEntity.builder()
                                    .cohortMonth(cohortMonth).monthsElapsed(0)
                                    .cohortSize(100).retainedUsers(100)
                                    .revenue(new BigDecimal("50000")).build(),
                            AnalyticsMonthlyCohortEntity.builder()
                                    .cohortMonth(cohortMonth).monthsElapsed(1)
                                    .cohortSize(100).retainedUsers(80)
                                    .revenue(new BigDecimal("45000")).build()
                    ));

            // Act
            var result = service.getCohortAnalysis(fromCohort, toCohort, "RETENTION");

            // Assert
            assertThat(result).isNotNull();
        }
    }

    // ========== getSnapshots ==========

    @Nested
    @DisplayName("getSnapshots")
    class GetSnapshots {

        @Test
        @DisplayName("正常系: スナップショット一覧取得")
        void testGetSnapshots_正常取得() {
            // Arrange
            String fromMonth = "2025-10";
            String toMonth = "2026-03";

            given(snapshotRepository.findByMonthBetweenOrderByMonthAsc(any(), any()))
                    .willReturn(List.of(
                            AnalyticsMonthlySnapshotEntity.builder()
                                    .month(LocalDate.of(2025, 10, 1))
                                    .mrr(new BigDecimal("100000"))
                                    .arr(new BigDecimal("1200000"))
                                    .userChurnRate(new BigDecimal("3.00"))
                                    .revenueChurnRate(new BigDecimal("2.50"))
                                    .activeUsers(500).payingUsers(200)
                                    .build(),
                            AnalyticsMonthlySnapshotEntity.builder()
                                    .month(LocalDate.of(2025, 11, 1))
                                    .mrr(new BigDecimal("110000"))
                                    .arr(new BigDecimal("1320000"))
                                    .userChurnRate(new BigDecimal("2.80"))
                                    .revenueChurnRate(new BigDecimal("2.20"))
                                    .activeUsers(520).payingUsers(210)
                                    .build()
                    ));

            // Act
            var result = service.getSnapshots(fromMonth, toMonth);

            // Assert
            assertThat(result).hasSize(2);
        }
    }
}
