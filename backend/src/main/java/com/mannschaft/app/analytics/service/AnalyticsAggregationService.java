package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.Granularity;
import com.mannschaft.app.analytics.dto.AdAnalyticsResponse;
import com.mannschaft.app.analytics.dto.ChurnAnalysisResponse;
import com.mannschaft.app.analytics.dto.CohortAnalysisResponse;
import com.mannschaft.app.analytics.dto.FunnelResponse;
import com.mannschaft.app.analytics.dto.KpiSnapshotResponse;
import com.mannschaft.app.analytics.dto.ModuleRankingResponse;
import com.mannschaft.app.analytics.dto.RevenueBySourceResponse;
import com.mannschaft.app.analytics.dto.RevenueSummaryResponse;
import com.mannschaft.app.analytics.dto.RevenueTrendResponse;
import com.mannschaft.app.analytics.dto.UserTrendResponse;
import com.mannschaft.app.analytics.entity.AnalyticsDailyAdsEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyModulesEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsFunnelEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyAdsRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyModulesRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsFunnelRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlySnapshotRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyConversionsRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyEngagementRepository;
import com.mannschaft.app.analytics.service.DateRangeResolver.DateRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 集計クエリを実行し、各APIの集計結果を返す中核サービス。
 *
 * <p>収益サマリ・推移・ユーザー動態・チャーン分析・コホート分析・ファネル分析・
 * モジュールランキング・広告分析・KPIスナップショットの集計を担当する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AnalyticsAggregationService {

    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;
    private final AnalyticsDailyModulesRepository modulesRepository;
    private final AnalyticsDailyAdsRepository adsRepository;
    private final AnalyticsDailyConversionsRepository conversionsRepository;
    private final AnalyticsDailyEngagementRepository engagementRepository;
    private final AnalyticsFunnelRepository funnelRepository;
    private final AnalyticsMonthlyCohortRepository cohortRepository;
    private final AnalyticsMonthlySnapshotRepository snapshotRepository;
    private final MetricCalculationService metricCalc;
    private final DateRangeResolver dateRangeResolver;

    /**
     * 収益サマリ（MRR/ARR/ARPU/LTV等）を算出する。
     *
     * @param date 基準日。null の場合は最新の集計済み日付を使用する。
     * @return 収益サマリレスポンス
     */
    public RevenueSummaryResponse getRevenueSummary(LocalDate date) {
        LocalDate targetDate = date != null ? date : resolveLatestDate();

        // 当月の日付範囲を算出
        LocalDate monthStart = targetDate.withDayOfMonth(1);
        LocalDate monthEnd = targetDate.with(TemporalAdjusters.lastDayOfMonth());

        BigDecimal mrr = metricCalc.calculateMrr(monthStart, monthEnd);
        BigDecimal arr = metricCalc.calculateArr(mrr);

        // アクティブユーザー数を集計（当月最終日のデータ）
        int activeUsers = getActiveUsersForDate(targetDate);
        BigDecimal arpu = metricCalc.calculateArpu(mrr, activeUsers);

        // チャーンレートの算出（当月データ）
        BigDecimal userChurnRate = calculateMonthlyUserChurnRate(monthStart, monthEnd);
        BigDecimal ltv = metricCalc.calculateLtv(arpu, userChurnRate);

        // 前月比の算出
        LocalDate prevMonthStart = monthStart.minusMonths(1);
        LocalDate prevMonthEnd = prevMonthStart.with(TemporalAdjusters.lastDayOfMonth());
        BigDecimal prevMrr = metricCalc.calculateMrr(prevMonthStart, prevMonthEnd);
        BigDecimal mrrGrowth = calculateGrowthRate(prevMrr, mrr);

        // 前年同月比の算出
        LocalDate yoyMonthStart = monthStart.minusYears(1);
        LocalDate yoyMonthEnd = yoyMonthStart.with(TemporalAdjusters.lastDayOfMonth());
        BigDecimal yoyMrr = metricCalc.calculateMrr(yoyMonthStart, yoyMonthEnd);
        BigDecimal yoyGrowth = calculateGrowthRate(yoyMrr, mrr);

        log.debug("収益サマリ算出完了: date={}, mrr={}, arr={}", targetDate, mrr, arr);

        return RevenueSummaryResponse.builder()
                .date(targetDate)
                .mrr(mrr)
                .arr(arr)
                .arpu(arpu)
                .ltv(ltv)
                .userChurnRate(userChurnRate)
                .mrrGrowthRate(mrrGrowth)
                .yoyGrowthRate(yoyGrowth)
                // TODO: Stripe照合ステータスは照合バッチと連携後に実装
                .stripeReconciliation("PENDING")
                .build();
    }

    /**
     * 収益推移を返す。granularity に応じて日次データを集約する。
     */
    public RevenueTrendResponse getRevenueTrend(LocalDate from, LocalDate to,
                                                DatePreset preset, Granularity granularity) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);
        dateRangeResolver.validateGranularity(range, granularity);

        List<AnalyticsDailyRevenueEntity> daily = revenueRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        List<RevenueTrendResponse.TrendPoint> points = switch (granularity) {
            case DAILY -> toDailyRevenueTrend(daily);
            case WEEKLY -> groupByWeek(daily);
            case MONTHLY -> groupByMonth(daily);
        };

        log.debug("収益推移取得完了: from={}, to={}, granularity={}, points={}",
                range.getFrom(), range.getTo(), granularity, points.size());

        return RevenueTrendResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .granularity(granularity)
                .data(points)
                .build();
    }

    /**
     * 収益源別内訳を返す。
     */
    public RevenueBySourceResponse getRevenueBySource(LocalDate from, LocalDate to,
                                                      DatePreset preset) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);

        List<AnalyticsDailyRevenueEntity> records = revenueRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        // revenueSource 別に集計
        Map<String, BigDecimal> bySource = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRevenueSource().name(),
                        Collectors.reducing(BigDecimal.ZERO,
                                AnalyticsDailyRevenueEntity::getNetRevenue,
                                BigDecimal::add)));

        BigDecimal total = bySource.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RevenueBySourceResponse.SourceItem> items = bySource.entrySet().stream()
                .map(e -> RevenueBySourceResponse.SourceItem.builder()
                        .source(e.getKey())
                        .revenue(e.getValue())
                        .percentage(total.compareTo(BigDecimal.ZERO) == 0
                                ? BigDecimal.ZERO
                                : e.getValue()
                                    .divide(total, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100)))
                        .build())
                .toList();

        log.debug("収益源別内訳取得完了: sources={}", items.size());

        return RevenueBySourceResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .total(total)
                .sources(items)
                .build();
    }

    /**
     * ユーザー動態推移を返す。
     */
    public UserTrendResponse getUserTrend(LocalDate from, LocalDate to,
                                          DatePreset preset, Granularity granularity) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);
        dateRangeResolver.validateGranularity(range, granularity);

        List<AnalyticsDailyUsersEntity> daily = usersRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        List<UserTrendResponse.UserPoint> points = switch (granularity) {
            case DAILY -> toDailyUserTrend(daily);
            case WEEKLY -> groupUsersByWeek(daily);
            case MONTHLY -> groupUsersByMonth(daily);
        };

        log.debug("ユーザー動態推移取得完了: points={}", points.size());

        return UserTrendResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .granularity(granularity)
                .data(points)
                .build();
    }

    /**
     * 解約分析を返す。月次単位で user_churn_rate / revenue_churn_rate を計算する。
     */
    public ChurnAnalysisResponse getChurnAnalysis(LocalDate from, LocalDate to,
                                                  DatePreset preset) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);

        List<ChurnAnalysisResponse.ChurnMonth> months = new ArrayList<>();
        YearMonth startMonth = YearMonth.from(range.getFrom());
        YearMonth endMonth = YearMonth.from(range.getTo());

        for (YearMonth ym = startMonth; !ym.isAfter(endMonth); ym = ym.plusMonths(1)) {
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();

            BigDecimal userChurnRate = calculateMonthlyUserChurnRate(monthStart, monthEnd);
            BigDecimal revenueChurnRate = calculateMonthlyRevenueChurnRate(monthStart, monthEnd);

            months.add(ChurnAnalysisResponse.ChurnMonth.builder()
                    .month(ym.toString())
                    .userChurnRate(userChurnRate)
                    .revenueChurnRate(revenueChurnRate)
                    .build());
        }

        log.debug("解約分析取得完了: months={}", months.size());

        return ChurnAnalysisResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .data(months)
                .build();
    }

    /**
     * コホート分析を返す。
     *
     * @param fromCohort 開始コホート（YYYY-MM形式）
     * @param toCohort   終了コホート（YYYY-MM形式）
     * @param metric     分析指標（RETENTION or REVENUE）
     */
    public CohortAnalysisResponse getCohortAnalysis(String fromCohort, String toCohort,
                                                    String metric) {
        List<AnalyticsMonthlyCohortEntity> records = cohortRepository
                .findByCohortMonthBetweenOrderByCohortMonthAscOffsetMonthsAsc(fromCohort, toCohort);

        // コホート月ごとにグループ化
        Map<String, List<AnalyticsMonthlyCohortEntity>> byCohort = records.stream()
                .collect(Collectors.groupingBy(
                        AnalyticsMonthlyCohortEntity::getCohortMonth,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<CohortAnalysisResponse.CohortRow> rows = byCohort.entrySet().stream()
                .map(entry -> buildCohortRow(entry.getKey(), entry.getValue(), metric))
                .toList();

        log.debug("コホート分析取得完了: cohorts={}", rows.size());

        return CohortAnalysisResponse.builder()
                .fromCohort(fromCohort)
                .toCohort(toCohort)
                .metric(metric)
                .cohorts(rows)
                .build();
    }

    /**
     * ファネル分析を返す。
     */
    public FunnelResponse getFunnelAnalysis(LocalDate date) {
        LocalDate targetDate = date != null ? date : resolveLatestDate();

        List<AnalyticsFunnelEntity> stages = funnelRepository
                .findByDateOrderByStageAsc(targetDate);

        List<FunnelResponse.FunnelStage> funnelStages = new ArrayList<>();
        int previousCount = 0;

        for (int i = 0; i < stages.size(); i++) {
            AnalyticsFunnelEntity stage = stages.get(i);
            BigDecimal conversionRate;
            if (i == 0) {
                conversionRate = BigDecimal.valueOf(100);
                previousCount = stage.getUserCount();
            } else {
                conversionRate = previousCount == 0
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(stage.getUserCount())
                            .divide(BigDecimal.valueOf(previousCount), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                previousCount = stage.getUserCount();
            }

            funnelStages.add(FunnelResponse.FunnelStage.builder()
                    .stage(stage.getStage())
                    .userCount(stage.getUserCount())
                    .conversionRate(conversionRate)
                    .build());
        }

        log.debug("ファネル分析取得完了: date={}, stages={}", targetDate, funnelStages.size());

        return FunnelResponse.builder()
                .date(targetDate)
                .stages(funnelStages)
                .build();
    }

    /**
     * モジュールランキングを返す。
     *
     * @param sortBy ソート基準（active_teams, revenue, growth_rate）
     */
    public ModuleRankingResponse getModuleRanking(LocalDate from, LocalDate to,
                                                  DatePreset preset, String sortBy) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);

        List<AnalyticsDailyModulesEntity> records = modulesRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        // モジュール別に集計
        Map<String, List<AnalyticsDailyModulesEntity>> byModule = records.stream()
                .collect(Collectors.groupingBy(AnalyticsDailyModulesEntity::getModuleKey));

        BigDecimal totalRevenue = records.stream()
                .map(AnalyticsDailyModulesEntity::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 前期の日付範囲を算出
        long periodDays = ChronoUnit.DAYS.between(range.getFrom(), range.getTo()) + 1;
        LocalDate prevFrom = range.getFrom().minusDays(periodDays);
        LocalDate prevTo = range.getFrom().minusDays(1);
        List<AnalyticsDailyModulesEntity> prevRecords = modulesRepository
                .findByDateBetweenOrderByDateAsc(prevFrom, prevTo);
        Map<String, Integer> prevTeamsByModule = prevRecords.stream()
                .collect(Collectors.groupingBy(
                        AnalyticsDailyModulesEntity::getModuleKey,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.isEmpty() ? 0
                                        : list.get(list.size() - 1).getActiveTeams())));

        List<ModuleRankingResponse.ModuleItem> items = byModule.entrySet().stream()
                .map(entry -> {
                    String moduleKey = entry.getKey();
                    List<AnalyticsDailyModulesEntity> moduleRecords = entry.getValue();

                    BigDecimal revenue = moduleRecords.stream()
                            .map(AnalyticsDailyModulesEntity::getRevenue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    int latestActiveTeams = moduleRecords.isEmpty() ? 0
                            : moduleRecords.get(moduleRecords.size() - 1).getActiveTeams();

                    BigDecimal revenueSharePct = totalRevenue.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : revenue.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));

                    int prevTeams = prevTeamsByModule.getOrDefault(moduleKey, 0);
                    BigDecimal growthRate = prevTeams == 0
                            ? null
                            : BigDecimal.valueOf(latestActiveTeams - prevTeams)
                                .divide(BigDecimal.valueOf(prevTeams), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));

                    return ModuleRankingResponse.ModuleItem.builder()
                            .moduleKey(moduleKey)
                            .activeTeams(latestActiveTeams)
                            .revenue(revenue)
                            .revenueSharePct(revenueSharePct)
                            .growthRate(growthRate)
                            .build();
                })
                .toList();

        log.debug("モジュールランキング取得完了: modules={}", items.size());

        return ModuleRankingResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .sortBy(sortBy)
                .modules(items)
                .build();
    }

    /**
     * 広告分析を返す。
     */
    public AdAnalyticsResponse getAdAnalytics(LocalDate from, LocalDate to,
                                              DatePreset preset, Granularity granularity) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);
        dateRangeResolver.validateGranularity(range, granularity);

        List<AnalyticsDailyAdsEntity> records = adsRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        // サマリの算出
        long totalImpressions = records.stream()
                .mapToLong(AnalyticsDailyAdsEntity::getImpressions).sum();
        long totalClicks = records.stream()
                .mapToLong(AnalyticsDailyAdsEntity::getClicks).sum();
        BigDecimal totalRevenue = records.stream()
                .map(AnalyticsDailyAdsEntity::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgCtr = totalImpressions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalClicks)
                    .divide(BigDecimal.valueOf(totalImpressions), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

        AdAnalyticsResponse.AdSummary summary = AdAnalyticsResponse.AdSummary.builder()
                .totalImpressions(totalImpressions)
                .totalClicks(totalClicks)
                .totalRevenue(totalRevenue)
                .averageCtr(avgCtr)
                .build();

        log.debug("広告分析取得完了: impressions={}, clicks={}", totalImpressions, totalClicks);

        return AdAnalyticsResponse.builder()
                .from(range.getFrom())
                .to(range.getTo())
                .granularity(granularity)
                .summary(summary)
                .build();
    }

    /**
     * スナップショット一覧を返す。
     *
     * @param fromMonth 開始月（YYYY-MM形式）
     * @param toMonth   終了月（YYYY-MM形式）
     */
    public List<KpiSnapshotResponse> getSnapshots(String fromMonth, String toMonth) {
        List<AnalyticsMonthlySnapshotEntity> snapshots = snapshotRepository
                .findByMonthBetweenOrderByMonthAsc(fromMonth, toMonth);

        return snapshots.stream()
                .map(s -> KpiSnapshotResponse.builder()
                        .month(s.getMonth())
                        .mrr(s.getMrr())
                        .arr(s.getArr())
                        .arpu(s.getArpu())
                        .ltv(s.getLtv())
                        .userChurnRate(s.getUserChurnRate())
                        .revenueChurnRate(s.getRevenueChurnRate())
                        .nrr(s.getNrr())
                        .activeUsers(s.getActiveUsers())
                        .payingUsers(s.getPayingUsers())
                        .build())
                .toList();
    }

    // ========== ヘルパーメソッド ==========

    private LocalDate resolveLatestDate() {
        return revenueRepository.findMaxDate()
                .orElse(LocalDate.now());
    }

    private int getActiveUsersForDate(LocalDate date) {
        return usersRepository.findByDate(date)
                .map(AnalyticsDailyUsersEntity::getActiveUsers)
                .orElse(0);
    }

    private BigDecimal calculateMonthlyUserChurnRate(LocalDate monthStart, LocalDate monthEnd) {
        var startData = usersRepository.findByDate(monthStart);
        var endData = usersRepository.findByDate(monthEnd);
        int beginningPaying = startData.map(AnalyticsDailyUsersEntity::getPayingUsers).orElse(0);
        int churnedUsers = endData.map(AnalyticsDailyUsersEntity::getChurnedUsers).orElse(0);
        return metricCalc.calculateUserChurnRate(churnedUsers, beginningPaying);
    }

    private BigDecimal calculateMonthlyRevenueChurnRate(LocalDate monthStart,
                                                        LocalDate monthEnd) {
        // 月初MRR と解約MRR をスナップショットから取得
        String month = YearMonth.from(monthStart).toString();
        var snapshot = snapshotRepository.findByMonth(month);
        if (snapshot.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var s = snapshot.get();
        return metricCalc.calculateRevenueChurnRate(
                s.getChurnedMrr() != null ? s.getChurnedMrr() : BigDecimal.ZERO,
                s.getMrr() != null ? s.getMrr() : BigDecimal.ZERO);
    }

    private BigDecimal calculateGrowthRate(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    // ----- 日次 → 日次トレンド変換 -----

    private List<RevenueTrendResponse.TrendPoint> toDailyRevenueTrend(
            List<AnalyticsDailyRevenueEntity> records) {
        // 日付ごとに集約（同日に複数 revenueSource がある場合）
        Map<LocalDate, BigDecimal> byDate = records.stream()
                .collect(Collectors.groupingBy(
                        AnalyticsDailyRevenueEntity::getDate,
                        LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                AnalyticsDailyRevenueEntity::getNetRevenue,
                                BigDecimal::add)));

        return byDate.entrySet().stream()
                .map(e -> RevenueTrendResponse.TrendPoint.builder()
                        .label(e.getKey().toString())
                        .value(e.getValue())
                        .build())
                .toList();
    }

    private List<UserTrendResponse.UserPoint> toDailyUserTrend(
            List<AnalyticsDailyUsersEntity> records) {
        return records.stream()
                .map(r -> UserTrendResponse.UserPoint.builder()
                        .label(r.getDate().toString())
                        .activeUsers(r.getActiveUsers())
                        .newUsers(r.getNewUsers())
                        .churnedUsers(r.getChurnedUsers())
                        .build())
                .toList();
    }

    // ----- WEEKLY グループ化 -----

    private List<RevenueTrendResponse.TrendPoint> groupByWeek(
            List<AnalyticsDailyRevenueEntity> records) {
        Map<LocalDate, BigDecimal> weeklyMap = new LinkedHashMap<>();
        for (AnalyticsDailyRevenueEntity r : records) {
            LocalDate weekStart = r.getDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyMap.merge(weekStart, r.getNetRevenue(), BigDecimal::add);
        }
        return weeklyMap.entrySet().stream()
                .map(e -> RevenueTrendResponse.TrendPoint.builder()
                        .label(e.getKey().toString())
                        .value(e.getValue())
                        .build())
                .toList();
    }

    private List<UserTrendResponse.UserPoint> groupUsersByWeek(
            List<AnalyticsDailyUsersEntity> records) {
        Map<LocalDate, List<AnalyticsDailyUsersEntity>> weeklyMap = new LinkedHashMap<>();
        for (AnalyticsDailyUsersEntity r : records) {
            LocalDate weekStart = r.getDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyMap.computeIfAbsent(weekStart, k -> new ArrayList<>()).add(r);
        }
        return weeklyMap.entrySet().stream()
                .map(e -> aggregateUserPoints(e.getKey().toString(), e.getValue()))
                .toList();
    }

    // ----- MONTHLY グループ化 -----

    private List<RevenueTrendResponse.TrendPoint> groupByMonth(
            List<AnalyticsDailyRevenueEntity> records) {
        Map<YearMonth, BigDecimal> monthlyMap = new LinkedHashMap<>();
        for (AnalyticsDailyRevenueEntity r : records) {
            YearMonth ym = YearMonth.from(r.getDate());
            monthlyMap.merge(ym, r.getNetRevenue(), BigDecimal::add);
        }
        return monthlyMap.entrySet().stream()
                .map(e -> RevenueTrendResponse.TrendPoint.builder()
                        .label(e.getKey().toString())
                        .value(e.getValue())
                        .build())
                .toList();
    }

    private List<UserTrendResponse.UserPoint> groupUsersByMonth(
            List<AnalyticsDailyUsersEntity> records) {
        Map<YearMonth, List<AnalyticsDailyUsersEntity>> monthlyMap = new LinkedHashMap<>();
        for (AnalyticsDailyUsersEntity r : records) {
            YearMonth ym = YearMonth.from(r.getDate());
            monthlyMap.computeIfAbsent(ym, k -> new ArrayList<>()).add(r);
        }
        return monthlyMap.entrySet().stream()
                .map(e -> aggregateUserPoints(e.getKey().toString(), e.getValue()))
                .toList();
    }

    private UserTrendResponse.UserPoint aggregateUserPoints(
            String label, List<AnalyticsDailyUsersEntity> group) {
        // 期間内の最終日のアクティブユーザー数、新規・解約は合算
        AnalyticsDailyUsersEntity last = group.get(group.size() - 1);
        int totalNew = group.stream().mapToInt(AnalyticsDailyUsersEntity::getNewUsers).sum();
        int totalChurned = group.stream().mapToInt(AnalyticsDailyUsersEntity::getChurnedUsers).sum();

        return UserTrendResponse.UserPoint.builder()
                .label(label)
                .activeUsers(last.getActiveUsers())
                .newUsers(totalNew)
                .churnedUsers(totalChurned)
                .build();
    }

    private CohortAnalysisResponse.CohortRow buildCohortRow(
            String cohortMonth,
            List<AnalyticsMonthlyCohortEntity> entries,
            String metric) {
        int cohortSize = entries.isEmpty() ? 0 : entries.get(0).getCohortSize();

        List<CohortAnalysisResponse.CohortCell> cells = entries.stream()
                .map(e -> {
                    BigDecimal value = "RETENTION".equalsIgnoreCase(metric)
                            ? (cohortSize == 0 ? BigDecimal.ZERO
                                : BigDecimal.valueOf(e.getRetainedUsers())
                                    .divide(BigDecimal.valueOf(cohortSize), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100)))
                            : e.getRevenue();

                    return CohortAnalysisResponse.CohortCell.builder()
                            .offsetMonths(e.getOffsetMonths())
                            .value(value)
                            .build();
                })
                .toList();

        return CohortAnalysisResponse.CohortRow.builder()
                .cohortMonth(cohortMonth)
                .cohortSize(cohortSize)
                .cells(cells)
                .build();
    }
}
