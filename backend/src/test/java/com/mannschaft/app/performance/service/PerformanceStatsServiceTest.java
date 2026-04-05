package com.mannschaft.app.performance.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.dto.MemberPerformanceResponse;
import com.mannschaft.app.performance.dto.MyPerformanceResponse;
import com.mannschaft.app.performance.dto.SchedulePerformanceResponse;
import com.mannschaft.app.performance.dto.TeamStatsResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

/**
 * {@link PerformanceStatsService} の単体テスト。
 * チーム統計・メンバー統計・自分のパフォーマンス・スケジュール紐付き統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceStatsService 単体テスト")
class PerformanceStatsServiceTest {

    @Mock
    private PerformanceRecordRepository recordRepository;

    @Mock
    private PerformanceMetricService metricService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private PerformanceStatsService performanceStatsService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long METRIC_ID = 100L;
    private static final Long USER_ID_1 = 10L;
    private static final Long USER_ID_2 = 20L;
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 3, 31);

    private PerformanceMetricEntity createMetric(Long id, String name, AggregationType aggType,
                                                  BigDecimal targetValue) {
        PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                .teamId(TEAM_ID)
                .name(name)
                .unit("km")
                .aggregationType(aggType)
                .targetValue(targetValue)
                .build();
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {}
        return entity;
    }

    private PerformanceRecordEntity createRecord(Long metricId, Long userId, BigDecimal value,
                                                  LocalDate date) {
        return PerformanceRecordEntity.builder()
                .metricId(metricId)
                .userId(userId)
                .recordedDate(date)
                .value(value)
                .build();
    }

    // ========================================
    // getTeamStats
    // ========================================

    @Nested
    @DisplayName("getTeamStats")
    class GetTeamStats {

        @Test
        @DisplayName("正常系: チーム統計が計算される")
        void getTeamStats_正常_統計が計算される() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, new BigDecimal("100"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("30"), DATE_FROM),
                    createRecord(METRIC_ID, USER_ID_2, new BigDecimal("20"), DATE_FROM.plusDays(1))
            );
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            TeamStatsResponse.MetricStats stats = response.getMetrics().get(0);
            assertThat(stats.getTeamTotal()).isEqualByComparingTo(new BigDecimal("50"));
            assertThat(stats.getTeamAvg()).isEqualByComparingTo(new BigDecimal("25.0000"));
            assertThat(stats.getAchievementRate()).isNotNull();
            assertThat(stats.getRanking()).hasSize(2);
            assertThat(stats.getRanking().get(0).getRank()).isEqualTo(1);
            assertThat(stats.getRanking().get(0).getValue()).isEqualByComparingTo(new BigDecimal("30"));
        }

        @Test
        @DisplayName("正常系: metricIdフィルタ指定時は単一指標のみ")
        void getTeamStats_metricIdフィルタ_単一指標() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, null);
            given(metricService.getMetricEntity(TEAM_ID, METRIC_ID)).willReturn(metric);
            given(recordRepository.findByMetricIdsAndDateRange(eq(List.of(METRIC_ID)), any(), any()))
                    .willReturn(List.of());

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, METRIC_ID, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            assertThat(response.getMetrics().get(0).getAchievementRate()).isNull();
        }

        @Test
        @DisplayName("正常系: 日付未指定で直近3ヶ月がデフォルト")
        void getTeamStats_日付未指定_デフォルト期間() {
            // Given
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of());

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, null, null);

            // Then
            assertThat(response.getPeriod().getFrom()).isEqualTo(LocalDate.now().minusMonths(3));
            assertThat(response.getPeriod().getTo()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("正常系: レコード0件でtotalとavgがゼロ")
        void getTeamStats_レコード0件_ゼロ() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, new BigDecimal("100"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));
            given(recordRepository.findByMetricIdsAndDateRange(any(), any(), any()))
                    .willReturn(List.of());

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            TeamStatsResponse.MetricStats stats = response.getMetrics().get(0);
            assertThat(stats.getTeamTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stats.getTeamAvg()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("正常系: 同値ランキングで同順位が割り当てられる")
        void getTeamStats_同値_同順位() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("50"), DATE_FROM),
                    createRecord(METRIC_ID, USER_ID_2, new BigDecimal("50"), DATE_FROM)
            );
            given(recordRepository.findByMetricIdsAndDateRange(any(), any(), any()))
                    .willReturn(records);

            // When
            TeamStatsResponse response = performanceStatsService.getTeamStats(TEAM_ID, null, DATE_FROM, DATE_TO);

            // Then
            List<TeamStatsResponse.RankingEntry> ranking = response.getMetrics().get(0).getRanking();
            assertThat(ranking).hasSize(2);
            assertThat(ranking.get(0).getRank()).isEqualTo(1);
            assertThat(ranking.get(1).getRank()).isEqualTo(1);
        }
    }

    // ========================================
    // getMemberPerformance
    // ========================================

    @Nested
    @DisplayName("getMemberPerformance")
    class GetMemberPerformance {

        @Test
        @DisplayName("正常系: メンバーのパフォーマンス統計が返る")
        void getMemberPerformance_正常_統計が返る() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, new BigDecimal("100"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("30"), LocalDate.of(2026, 1, 15)),
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("20"), LocalDate.of(2026, 2, 15))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(eq(USER_ID_1), any(), any(), any()))
                    .willReturn(records);

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getUserId()).isEqualTo(USER_ID_1);
            assertThat(response.getMetrics()).hasSize(1);
            MemberPerformanceResponse.MetricDetail detail = response.getMetrics().get(0);
            assertThat(detail.getTotal()).isEqualByComparingTo(new BigDecimal("50"));
            assertThat(detail.getRecordCount()).isEqualTo(2);
            assertThat(detail.getMax()).isEqualByComparingTo(new BigDecimal("30"));
            assertThat(detail.getMin()).isEqualByComparingTo(new BigDecimal("20"));
            assertThat(detail.getLatestValue()).isNotNull();
            assertThat(detail.getPreviousValue()).isNotNull();
            assertThat(detail.getChangeRate()).isNotNull();
        }

        @Test
        @DisplayName("正常系: レコード0件の指標はスキップ")
        void getMemberPerformance_レコード0件_スキップ() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(any(), any(), any(), any()))
                    .willReturn(List.of());

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).isEmpty();
        }

        @Test
        @DisplayName("正常系: LATEST集計タイプではpersonalBestがnull")
        void getMemberPerformance_LATEST集計_personalBestなし() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "体重", AggregationType.LATEST, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("70"), LocalDate.of(2026, 1, 1))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(any(), any(), any(), any()))
                    .willReturn(records);

            // When
            MemberPerformanceResponse response = performanceStatsService.getMemberPerformance(
                    TEAM_ID, USER_ID_1, DATE_FROM, DATE_TO);

            // Then
            assertThat(response.getMetrics()).hasSize(1);
            assertThat(response.getMetrics().get(0).getPersonalBest()).isNull();
        }
    }

    // ========================================
    // getMyPerformance
    // ========================================

    @Nested
    @DisplayName("getMyPerformance")
    class GetMyPerformance {

        @Test
        @DisplayName("正常系: teamId指定で自分のパフォーマンスが返る")
        void getMyPerformance_teamId指定_パフォーマンスが返る() {
            // Given
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(TEAM_ID, "TestTeam"));
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, new BigDecimal("100"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("40"), LocalDate.of(2026, 2, 1)),
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("60"), LocalDate.of(2026, 3, 1))
            );
            given(recordRepository.findByUserIdAndMetricIdsAndDateRange(eq(USER_ID_1), any(), any(), any()))
                    .willReturn(records);

            // When
            List<MyPerformanceResponse> result = performanceStatsService.getMyPerformance(
                    USER_ID_1, TEAM_ID, DATE_FROM, DATE_TO);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.get(0).getMetrics()).hasSize(1);
            assertThat(result.get(0).getMetrics().get(0).getTotal()).isEqualByComparingTo(new BigDecimal("100"));
        }

        @Test
        @DisplayName("正常系: teamIdがnullで空リストが返る")
        void getMyPerformance_teamIdなし_空リスト() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID_1)).willReturn(List.of());
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of());

            // When
            List<MyPerformanceResponse> result = performanceStatsService.getMyPerformance(
                    USER_ID_1, null, DATE_FROM, DATE_TO);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: metricIdsが空の場合は空リストが返る")
        void getMyPerformance_メトリクスなし_空リスト() {
            // Given
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(TEAM_ID, "TestTeam"));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of());

            // When
            List<MyPerformanceResponse> result = performanceStatsService.getMyPerformance(
                    USER_ID_1, TEAM_ID, DATE_FROM, DATE_TO);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getSchedulePerformance
    // ========================================

    @Nested
    @DisplayName("getSchedulePerformance")
    class GetSchedulePerformance {

        @Test
        @DisplayName("正常系: スケジュール紐付きパフォーマンスが返る")
        void getSchedulePerformance_正常_パフォーマンスが返る() {
            // Given
            Long scheduleId = 500L;
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", AggregationType.SUM, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("10"), LocalDate.of(2026, 2, 1))
            );
            given(recordRepository.findByScheduleIdOrderByUserIdAscMetricIdAsc(scheduleId))
                    .willReturn(records);

            // When
            SchedulePerformanceResponse response = performanceStatsService.getSchedulePerformance(TEAM_ID, scheduleId);

            // Then
            assertThat(response.getScheduleId()).isEqualTo(scheduleId);
            assertThat(response.getMembers()).hasSize(1);
            assertThat(response.getMembers().get(0).getUserId()).isEqualTo(USER_ID_1);
            assertThat(response.getRecordedDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        }

        @Test
        @DisplayName("正常系: レコード0件の場合recordedDateがnull")
        void getSchedulePerformance_レコード0件_recordedDateがnull() {
            // Given
            Long scheduleId = 500L;
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of());
            given(recordRepository.findByScheduleIdOrderByUserIdAscMetricIdAsc(scheduleId))
                    .willReturn(List.of());

            // When
            SchedulePerformanceResponse response = performanceStatsService.getSchedulePerformance(TEAM_ID, scheduleId);

            // Then
            assertThat(response.getRecordedDate()).isNull();
            assertThat(response.getMembers()).isEmpty();
        }
    }

    // ========================================
    // getActivityPerformance
    // ========================================

    @Nested
    @DisplayName("getActivityPerformance")
    class GetActivityPerformance {

        @Test
        @DisplayName("正常系: 活動記録紐付きパフォーマンスが返る")
        void getActivityPerformance_正常_パフォーマンスが返る() {
            // Given
            Long activityId = 600L;
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "スコア", AggregationType.MAX, null);
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            List<PerformanceRecordEntity> records = List.of(
                    createRecord(METRIC_ID, USER_ID_1, new BigDecimal("85"), LocalDate.of(2026, 3, 10))
            );
            given(recordRepository.findByActivityResultIdOrderByUserIdAscMetricIdAsc(activityId))
                    .willReturn(records);

            // When
            SchedulePerformanceResponse response = performanceStatsService.getActivityPerformance(TEAM_ID, activityId);

            // Then
            assertThat(response.getScheduleId()).isNull();
            assertThat(response.getScheduleName()).isEqualTo("Activity#" + activityId);
            assertThat(response.getMembers()).hasSize(1);
        }
    }
}
