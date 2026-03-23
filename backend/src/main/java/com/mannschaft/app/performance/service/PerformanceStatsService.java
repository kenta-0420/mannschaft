package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.dto.MemberPerformanceResponse;
import com.mannschaft.app.performance.dto.MyPerformanceResponse;
import com.mannschaft.app.performance.dto.SchedulePerformanceResponse;
import com.mannschaft.app.performance.dto.TeamStatsResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * パフォーマンス統計サービス。チーム統計・メンバー統計・自分のパフォーマンスを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceStatsService {

    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PerformanceRecordRepository recordRepository;
    private final PerformanceMetricService metricService;

    /**
     * チーム統計ダッシュボードを取得する。
     *
     * @param teamId   チームID
     * @param metricId 指標IDフィルタ（null の場合は全件）
     * @param dateFrom 期間開始日
     * @param dateTo   期間終了日
     * @return チーム統計レスポンス
     */
    public TeamStatsResponse getTeamStats(Long teamId, Long metricId, LocalDate dateFrom, LocalDate dateTo) {
        List<PerformanceMetricEntity> metrics;
        if (metricId != null) {
            PerformanceMetricEntity m = metricService.getMetricEntity(teamId, metricId);
            metrics = List.of(m);
        } else {
            metrics = metricService.getActiveMetrics(teamId);
        }

        LocalDate from = dateFrom != null ? dateFrom : LocalDate.now().minusMonths(3);
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();

        List<TeamStatsResponse.MetricStats> metricStats = new ArrayList<>();
        for (PerformanceMetricEntity metric : metrics) {
            List<PerformanceRecordEntity> records = recordRepository.findByMetricIdsAndDateRange(
                    List.of(metric.getId()), from, to);

            BigDecimal teamTotal = records.stream()
                    .map(PerformanceRecordEntity::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal teamAvg = records.isEmpty() ? BigDecimal.ZERO
                    : teamTotal.divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP);

            BigDecimal achievementRate = null;
            if (metric.getTargetValue() != null && metric.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal aggregatedValue = getAggregatedValue(metric.getAggregationType(), records);
                achievementRate = aggregatedValue.divide(metric.getTargetValue(), 4, RoundingMode.HALF_UP);
            }

            // ランキング: ユーザー別に集計
            Map<Long, BigDecimal> userTotals = records.stream()
                    .collect(Collectors.groupingBy(
                            PerformanceRecordEntity::getUserId,
                            Collectors.reducing(BigDecimal.ZERO, PerformanceRecordEntity::getValue, BigDecimal::add)
                    ));

            List<Map.Entry<Long, BigDecimal>> sorted = userTotals.entrySet().stream()
                    .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                    .toList();

            List<TeamStatsResponse.RankingEntry> ranking = new ArrayList<>();
            int rank = 0;
            BigDecimal prevValue = null;
            for (int i = 0; i < sorted.size(); i++) {
                Map.Entry<Long, BigDecimal> entry = sorted.get(i);
                if (prevValue == null || prevValue.compareTo(entry.getValue()) != 0) {
                    rank = i + 1;
                }
                prevValue = entry.getValue();
                ranking.add(new TeamStatsResponse.RankingEntry(
                        rank, entry.getKey(), "User#" + entry.getKey(), entry.getValue()));
            }

            metricStats.add(new TeamStatsResponse.MetricStats(
                    metric.getId(), metric.getName(), metric.getUnit(),
                    metric.getAggregationType().name(),
                    teamTotal, teamAvg, metric.getTargetValue(), achievementRate, ranking));
        }

        return new TeamStatsResponse(metricStats, new TeamStatsResponse.Period(from, to));
    }

    /**
     * 特定メンバーのパフォーマンスを取得する。
     *
     * @param teamId   チームID
     * @param userId   ユーザーID
     * @param dateFrom 期間開始日
     * @param dateTo   期間終了日
     * @return メンバーパフォーマンスレスポンス
     */
    public MemberPerformanceResponse getMemberPerformance(Long teamId, Long userId,
                                                           LocalDate dateFrom, LocalDate dateTo) {
        List<PerformanceMetricEntity> metrics = metricService.getActiveMetrics(teamId);
        LocalDate from = dateFrom != null ? dateFrom : LocalDate.now().minusMonths(3);
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();

        List<Long> metricIds = metrics.stream().map(PerformanceMetricEntity::getId).toList();
        List<PerformanceRecordEntity> allRecords = recordRepository.findByUserIdAndMetricIdsAndDateRange(
                userId, metricIds, from, to);

        Map<Long, List<PerformanceRecordEntity>> byMetric = allRecords.stream()
                .collect(Collectors.groupingBy(PerformanceRecordEntity::getMetricId));

        List<MemberPerformanceResponse.MetricDetail> details = new ArrayList<>();
        for (PerformanceMetricEntity metric : metrics) {
            List<PerformanceRecordEntity> records = byMetric.getOrDefault(metric.getId(), List.of());
            if (records.isEmpty()) continue;

            BigDecimal total = records.stream().map(PerformanceRecordEntity::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = total.divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP);
            BigDecimal max = records.stream().map(PerformanceRecordEntity::getValue).max(Comparator.naturalOrder()).orElse(null);
            BigDecimal min = records.stream().map(PerformanceRecordEntity::getValue).min(Comparator.naturalOrder()).orElse(null);

            BigDecimal achievementRate = null;
            if (metric.getTargetValue() != null && metric.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal aggregated = getAggregatedValue(metric.getAggregationType(), records);
                achievementRate = aggregated.divide(metric.getTargetValue(), 4, RoundingMode.HALF_UP);
            }

            // latest and previous
            List<PerformanceRecordEntity> sortedRecords = records.stream()
                    .sorted(Comparator.comparing(PerformanceRecordEntity::getRecordedDate).reversed())
                    .toList();
            BigDecimal latestValue = sortedRecords.isEmpty() ? null : sortedRecords.get(0).getValue();
            BigDecimal previousValue = sortedRecords.size() >= 2 ? sortedRecords.get(1).getValue() : null;
            BigDecimal changeRate = null;
            if (previousValue != null && latestValue != null && previousValue.compareTo(BigDecimal.ZERO) != 0) {
                changeRate = latestValue.subtract(previousValue).divide(previousValue, 4, RoundingMode.HALF_UP);
            }

            // personal best
            MemberPerformanceResponse.PersonalBest personalBest = null;
            if (metric.getAggregationType() != AggregationType.LATEST) {
                PerformanceRecordEntity bestRecord = switch (metric.getAggregationType()) {
                    case MIN -> records.stream().min(Comparator.comparing(PerformanceRecordEntity::getValue)).orElse(null);
                    default -> records.stream().max(Comparator.comparing(PerformanceRecordEntity::getValue)).orElse(null);
                };
                if (bestRecord != null) {
                    personalBest = new MemberPerformanceResponse.PersonalBest(bestRecord.getValue(), bestRecord.getRecordedDate());
                }
            }

            // trend: group by month
            Map<String, List<PerformanceRecordEntity>> byMonth = records.stream()
                    .collect(Collectors.groupingBy(r -> r.getRecordedDate().format(YEAR_MONTH_FMT),
                            LinkedHashMap::new, Collectors.toList()));
            List<MemberPerformanceResponse.MonthlyTrend> trend = byMonth.entrySet().stream()
                    .map(e -> new MemberPerformanceResponse.MonthlyTrend(
                            e.getKey(),
                            getAggregatedValue(metric.getAggregationType(), e.getValue())))
                    .toList();

            details.add(new MemberPerformanceResponse.MetricDetail(
                    metric.getId(), metric.getName(), metric.getUnit(), metric.getAggregationType().name(),
                    total, avg, max, min, records.size(), metric.getTargetValue(), achievementRate,
                    previousValue, latestValue, changeRate, personalBest, trend));
        }

        return new MemberPerformanceResponse(userId, "User#" + userId,
                new TeamStatsResponse.Period(from, to), details);
    }

    /**
     * 自分のパフォーマンスを全チーム横断で取得する。
     *
     * @param currentUserId 現在のユーザーID
     * @param teamId        チームIDフィルタ
     * @param dateFrom      期間開始日
     * @param dateTo        期間終了日
     * @return 自分のパフォーマンスレスポンスリスト
     */
    public List<MyPerformanceResponse> getMyPerformance(Long currentUserId, Long teamId,
                                                         LocalDate dateFrom, LocalDate dateTo) {
        // ユーザーの所属チーム一覧は UserRoleRepository 経由で取得予定
        // For now, if teamId is specified, return that team only
        if (teamId != null) {
            List<PerformanceMetricEntity> metrics = metricService.getActiveMetrics(teamId);
            List<Long> metricIds = metrics.stream().map(PerformanceMetricEntity::getId).toList();
            if (metricIds.isEmpty()) {
                return List.of();
            }

            List<PerformanceRecordEntity> records = recordRepository.findByUserIdAndMetricIdsAndDateRange(
                    currentUserId, metricIds, dateFrom, dateTo);

            Map<Long, List<PerformanceRecordEntity>> byMetric = records.stream()
                    .collect(Collectors.groupingBy(PerformanceRecordEntity::getMetricId));

            List<MyPerformanceResponse.MetricSummary> summaries = new ArrayList<>();
            for (PerformanceMetricEntity metric : metrics) {
                List<PerformanceRecordEntity> metricRecords = byMetric.getOrDefault(metric.getId(), List.of());
                if (metricRecords.isEmpty()) continue;

                BigDecimal total = metricRecords.stream().map(PerformanceRecordEntity::getValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal achievementRate = null;
                if (metric.getTargetValue() != null && metric.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal aggregated = getAggregatedValue(metric.getAggregationType(), metricRecords);
                    achievementRate = aggregated.divide(metric.getTargetValue(), 4, RoundingMode.HALF_UP);
                }

                List<PerformanceRecordEntity> sorted = metricRecords.stream()
                        .sorted(Comparator.comparing(PerformanceRecordEntity::getRecordedDate).reversed())
                        .toList();
                BigDecimal latestValue = sorted.isEmpty() ? null : sorted.get(0).getValue();
                BigDecimal previousValue = sorted.size() >= 2 ? sorted.get(1).getValue() : null;
                BigDecimal changeRate = null;
                if (previousValue != null && latestValue != null && previousValue.compareTo(BigDecimal.ZERO) != 0) {
                    changeRate = latestValue.subtract(previousValue).divide(previousValue, 4, RoundingMode.HALF_UP);
                }

                MyPerformanceResponse.LatestRecord latestRecord = sorted.isEmpty() ? null
                        : new MyPerformanceResponse.LatestRecord(sorted.get(0).getRecordedDate(),
                        sorted.get(0).getValue(), sorted.get(0).getNote());

                summaries.add(new MyPerformanceResponse.MetricSummary(
                        metric.getId(), metric.getName(), metric.getUnit(), metric.getAggregationType().name(),
                        total, metricRecords.size(), metric.getTargetValue(), achievementRate,
                        previousValue, latestValue, changeRate, latestRecord));
            }

            return List.of(new MyPerformanceResponse(teamId, "Team#" + teamId, summaries));
        }

        return List.of();
    }

    /**
     * スケジュール紐付きパフォーマンス一覧を取得する。
     *
     * @param teamId     チームID
     * @param scheduleId スケジュールID
     * @return スケジュールパフォーマンスレスポンス
     */
    public SchedulePerformanceResponse getSchedulePerformance(Long teamId, Long scheduleId) {
        List<PerformanceRecordEntity> records = recordRepository.findByScheduleIdOrderByUserIdAscMetricIdAsc(scheduleId);
        List<PerformanceMetricEntity> metrics = metricService.getActiveMetrics(teamId);
        Map<Long, PerformanceMetricEntity> metricMap = metrics.stream()
                .collect(Collectors.toMap(PerformanceMetricEntity::getId, m -> m));

        Map<Long, List<PerformanceRecordEntity>> byUser = records.stream()
                .collect(Collectors.groupingBy(PerformanceRecordEntity::getUserId, LinkedHashMap::new, Collectors.toList()));

        LocalDate recordedDate = records.isEmpty() ? null : records.get(0).getRecordedDate();

        List<SchedulePerformanceResponse.MemberRecords> members = byUser.entrySet().stream()
                .map(entry -> {
                    List<SchedulePerformanceResponse.RecordEntry> recordEntries = entry.getValue().stream()
                            .map(r -> {
                                PerformanceMetricEntity m = metricMap.get(r.getMetricId());
                                return new SchedulePerformanceResponse.RecordEntry(
                                        r.getMetricId(),
                                        m != null ? m.getName() : "Unknown",
                                        r.getValue(),
                                        m != null ? m.getUnit() : null);
                            })
                            .toList();
                    return new SchedulePerformanceResponse.MemberRecords(
                            entry.getKey(), "User#" + entry.getKey(), recordEntries);
                })
                .toList();

        return new SchedulePerformanceResponse(scheduleId, "Schedule#" + scheduleId, recordedDate, members);
    }

    /**
     * 活動記録紐付きパフォーマンス一覧を取得する。
     *
     * @param teamId     チームID
     * @param activityId 活動記録ID
     * @return スケジュールパフォーマンスレスポンス（同じフォーマットを再利用）
     */
    public SchedulePerformanceResponse getActivityPerformance(Long teamId, Long activityId) {
        List<PerformanceRecordEntity> records = recordRepository.findByActivityResultIdOrderByUserIdAscMetricIdAsc(activityId);
        List<PerformanceMetricEntity> metrics = metricService.getActiveMetrics(teamId);
        Map<Long, PerformanceMetricEntity> metricMap = metrics.stream()
                .collect(Collectors.toMap(PerformanceMetricEntity::getId, m -> m));

        Map<Long, List<PerformanceRecordEntity>> byUser = records.stream()
                .collect(Collectors.groupingBy(PerformanceRecordEntity::getUserId, LinkedHashMap::new, Collectors.toList()));

        LocalDate recordedDate = records.isEmpty() ? null : records.get(0).getRecordedDate();

        List<SchedulePerformanceResponse.MemberRecords> members = byUser.entrySet().stream()
                .map(entry -> {
                    List<SchedulePerformanceResponse.RecordEntry> recordEntries = entry.getValue().stream()
                            .map(r -> {
                                PerformanceMetricEntity m = metricMap.get(r.getMetricId());
                                return new SchedulePerformanceResponse.RecordEntry(
                                        r.getMetricId(),
                                        m != null ? m.getName() : "Unknown",
                                        r.getValue(),
                                        m != null ? m.getUnit() : null);
                            })
                            .toList();
                    return new SchedulePerformanceResponse.MemberRecords(
                            entry.getKey(), "User#" + entry.getKey(), recordEntries);
                })
                .toList();

        return new SchedulePerformanceResponse(null, "Activity#" + activityId, recordedDate, members);
    }

    /**
     * aggregation_type に基づいて集計値を算出する。
     */
    private BigDecimal getAggregatedValue(AggregationType type, List<PerformanceRecordEntity> records) {
        if (records.isEmpty()) return BigDecimal.ZERO;

        return switch (type) {
            case SUM -> records.stream().map(PerformanceRecordEntity::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> {
                BigDecimal sum = records.stream().map(PerformanceRecordEntity::getValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                yield sum.divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP);
            }
            case MAX -> records.stream().map(PerformanceRecordEntity::getValue)
                    .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            case MIN -> records.stream().map(PerformanceRecordEntity::getValue)
                    .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            case LATEST -> records.stream()
                    .max(Comparator.comparing(PerformanceRecordEntity::getRecordedDate))
                    .map(PerformanceRecordEntity::getValue)
                    .orElse(BigDecimal.ZERO);
        };
    }
}
