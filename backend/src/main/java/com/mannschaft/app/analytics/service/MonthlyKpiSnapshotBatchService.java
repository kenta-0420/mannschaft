package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// TODO: ShedLock 導入後に @SchedulerLock を有効化する
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 月次 KPI スナップショットバッチ。毎月1日 AM 4:00 (JST) に前月のKPIを保存する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyKpiSnapshotBatchService {

    private final AnalyticsMonthlySnapshotRepository snapshotRepository;
    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;
    private final MetricCalculationService metricCalculation;

    @Scheduled(cron = "0 0 4 1 * *", zone = "Asia/Tokyo")
    // TODO: @SchedulerLock(name = "monthlyKpiSnapshot", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void execute() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        LocalDate targetMonth = now.minusMonths(1).withDayOfMonth(1);
        LocalDate monthEnd = targetMonth.with(TemporalAdjusters.lastDayOfMonth());
        log.info("月次KPIスナップショットバッチ開始: month={}", targetMonth);

        try {
            createSnapshot(targetMonth, monthEnd);
            log.info("月次KPIスナップショットバッチ完了");
        } catch (Exception e) {
            log.error("月次KPIスナップショットバッチ失敗", e);
            throw e;
        }
    }

    private void createSnapshot(LocalDate monthStart, LocalDate monthEnd) {
        // MRR / 全指標を MetricCalculationService で算出
        BigDecimal mrr = metricCalculation.calculateMrr(monthStart, monthEnd);
        BigDecimal arr = metricCalculation.calculateArr(mrr);

        // ユーザー指標: 月末日の analytics_daily_users
        var monthEndUsers = usersRepository.findByDate(monthEnd);
        int activeUsers = monthEndUsers.map(AnalyticsDailyUsersEntity::getActiveUsers).orElse(0);
        int payingUsers = monthEndUsers.map(AnalyticsDailyUsersEntity::getPayingUsers).orElse(0);
        int totalUsers = monthEndUsers.map(AnalyticsDailyUsersEntity::getTotalUsers).orElse(0);

        // 月間合計: new_users, churned_users
        List<AnalyticsDailyUsersEntity> monthlyUsers =
                usersRepository.findByDateBetweenOrderByDateAsc(monthStart, monthEnd);
        int newUsers = monthlyUsers.stream().mapToInt(AnalyticsDailyUsersEntity::getNewUsers).sum();
        int churnedUsers = monthlyUsers.stream().mapToInt(AnalyticsDailyUsersEntity::getChurnedUsers).sum();

        // 月間純収益
        List<AnalyticsDailyRevenueEntity> monthlyRevenue =
                revenueRepository.findByDateBetweenOrderByDateAsc(monthStart, monthEnd);
        BigDecimal netRevenue = monthlyRevenue.stream()
                .map(AnalyticsDailyRevenueEntity::getNetRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adRevenue = monthlyRevenue.stream()
                .filter(r -> r.getRevenueSource() == RevenueSource.ADVERTISING)
                .map(AnalyticsDailyRevenueEntity::getNetRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // KPI 計算
        BigDecimal arpu = metricCalculation.calculateArpu(netRevenue, activeUsers);
        BigDecimal userChurnRate = metricCalculation.calculateUserChurnRate(churnedUsers, payingUsers);
        BigDecimal ltv = metricCalculation.calculateLtv(arpu, userChurnRate);
        BigDecimal revenueChurnRate = BigDecimal.ZERO; // TODO: 前月MRRからの計算

        AnalyticsMonthlySnapshotEntity snapshot = AnalyticsMonthlySnapshotEntity.builder()
                .month(monthStart)
                .mrr(mrr)
                .arr(arr)
                .arpu(arpu)
                .ltv(ltv)
                .totalUsers(totalUsers)
                .activeUsers(activeUsers)
                .payingUsers(payingUsers)
                .newUsers(newUsers)
                .churnedUsers(churnedUsers)
                .userChurnRate(userChurnRate)
                .revenueChurnRate(revenueChurnRate)
                .netRevenue(netRevenue)
                .adRevenue(adRevenue)
                .build();

        snapshotRepository.save(snapshot);

        // TODO: SYSTEM_ADMIN へ月次レポートメール自動送信（F09.6 SES連携）
    }
}
