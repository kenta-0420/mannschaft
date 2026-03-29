package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.FunnelStage;
import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsFunnelSnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsFunnelSnapshotRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.repository.TeamEnabledModuleRepository;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 日次集計バッチ。毎日 AM 2:00 (JST) に前日分のデータを集計する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyAggregationBatchService {

    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;
    private final AnalyticsFunnelSnapshotRepository funnelRepository;
    private final AnalyticsAlertService alertService;
    private final MemberPaymentRepository memberPaymentRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamEnabledModuleRepository teamEnabledModuleRepository;
    private final TeamSubscriptionRepository teamSubscriptionRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;

    /**
     * 日次集計バッチを実行する。
     * ShedLock による排他制御あり。最大ロック30分。
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "dailyAnalyticsAggregation", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void execute() {
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(1);
        log.info("日次集計バッチ開始: date={}", yesterday);

        try {
            aggregateRevenue(yesterday);
            aggregateUsers(yesterday);
            aggregateModules(yesterday);
            aggregateAds(yesterday);
            aggregateFunnel(yesterday);

            log.info("日次集計バッチ完了: date={}", yesterday);

            // バッチ完了後にアラート評価
            alertService.evaluateAlerts(yesterday);
        } catch (Exception e) {
            log.error("日次集計バッチ失敗: date={}", yesterday, e);
            throw e;
        }
    }

    /**
     * 指定日の集計を実行する（バックフィル用の公開メソッド）。
     */
    @Transactional
    public void aggregateForDate(LocalDate date) {
        aggregateRevenue(date);
        aggregateUsers(date);
        aggregateModules(date);
        aggregateAds(date);
        aggregateFunnel(date);
    }

    private void aggregateRevenue(LocalDate date) {
        log.debug("収益集計: date={}", date);

        // 冪等性: 既存データを削除してから再集計
        revenueRepository.deleteByDate(date);

        // member_payments テーブルからの実データ集計
        BigDecimal grossRevenue = memberPaymentRepository.sumPaidAmountByDate(date);
        BigDecimal refundAmount = memberPaymentRepository.sumRefundedAmountByDate(date);
        BigDecimal netRevenue = grossRevenue.subtract(refundAmount);
        int transactionCount = memberPaymentRepository.countPaidByDate(date);

        // 現時点では revenue_source の判別が member_payments テーブルに含まれないため
        // ONE_TIME_PAYMENT として一括集計する。将来的に PaymentItem.type から分岐予定。
        for (RevenueSource source : RevenueSource.values()) {
            AnalyticsDailyRevenueEntity entity;
            if (source == RevenueSource.ONE_TIME_PAYMENT) {
                entity = AnalyticsDailyRevenueEntity.builder()
                        .date(date)
                        .revenueSource(source)
                        .grossRevenue(grossRevenue)
                        .refundAmount(refundAmount)
                        .netRevenue(netRevenue)
                        .transactionCount(transactionCount)
                        .build();
            } else if (source == RevenueSource.ADVERTISING) {
                // 広告収益は ad_daily_stats から集計
                var adStats = adDailyStatsRepository.findByDateBetween(date, date);
                BigDecimal adRevenue = adStats.stream()
                        .map(s -> s.getCost() != null ? s.getCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                entity = AnalyticsDailyRevenueEntity.builder()
                        .date(date)
                        .revenueSource(source)
                        .grossRevenue(adRevenue)
                        .netRevenue(adRevenue)
                        .transactionCount(adStats.size())
                        .build();
            } else {
                // 他の収益源は将来実装。現時点では空データ
                entity = AnalyticsDailyRevenueEntity.builder()
                        .date(date)
                        .revenueSource(source)
                        .build();
            }
            revenueRepository.save(entity);
        }
    }

    private void aggregateUsers(LocalDate date) {
        log.debug("ユーザー集計: date={}", date);

        // 冪等性: 既存データを削除してから再集計
        usersRepository.deleteByDate(date);

        LocalDateTime endOfDay = LocalDateTime.of(date, LocalTime.MAX);

        int newUsers = userRepository.countNewUsersByDate(date);
        int activeUsers = userRepository.countActiveUsersAsOf(endOfDay);
        int totalUsers = userRepository.countTotalUsersAsOf(endOfDay);
        int payingUsers = memberPaymentRepository.countDistinctPayingUsersByDate(date);

        // churned = 前日の paying - 当日の paying（簡易計算、0 以上に制限）
        int previousPayingUsers = memberPaymentRepository
                .countDistinctPayingUsersByDate(date.minusDays(1));
        int churnedUsers = Math.max(0, previousPayingUsers - payingUsers);

        AnalyticsDailyUsersEntity entity = AnalyticsDailyUsersEntity.builder()
                .date(date)
                .newUsers(newUsers)
                .activeUsers(activeUsers)
                .payingUsers(payingUsers)
                .churnedUsers(churnedUsers)
                .totalUsers(totalUsers)
                .build();
        usersRepository.save(entity);
    }

    private void aggregateModules(LocalDate date) {
        log.debug("モジュール集計: date={}", date);
        // AnalyticsModuleStatsEntity が存在しないため、ログ出力のみ
        long enabledModuleCount = teamEnabledModuleRepository.count();
        log.info("モジュール集計: date={}, enabledModuleCount={}", date, enabledModuleCount);
    }

    private void aggregateAds(LocalDate date) {
        log.debug("広告集計: date={}", date);
        // ad_daily_stats は別プロセス（広告集計バッチ）で集計済み。存在確認のみ。
        var adStats = adDailyStatsRepository.findByDateBetween(date, date);
        log.info("広告集計: date={}, adDailyStatsCount={}", date, adStats.size());
    }

    private void aggregateFunnel(LocalDate date) {
        log.debug("ファネル集計: date={}", date);

        // 冪等性: 既存データを削除してから再集計
        funnelRepository.deleteByDate(date);

        LocalDateTime endOfDay = LocalDateTime.of(date, LocalTime.MAX);

        // REGISTERED: 全ユーザー数
        int registered = userRepository.countTotalUsersAsOf(endOfDay);

        // TEAM_JOINED: チームに所属しているユーザー数 ≈ アクティブチーム数（簡易）
        int teamJoined = teamRepository.countActiveTeamsAsOf(endOfDay);

        // MODULE_ACTIVATED: モジュールが有効化されているチーム数
        long moduleActivated = teamEnabledModuleRepository.count();

        // FIRST_PAYMENT: 少なくとも1回支払いをしたユーザー数
        int firstPayment = memberPaymentRepository.countDistinctPayingUsersByDate(date);

        // SUBSCRIBED: アクティブな有料サブスクリプション数
        int subscribed = teamSubscriptionRepository.countActivePaidSubscriptions();

        // RETAINED_3M/6M/12M: 3/6/12ヶ月前に登録し今もアクティブなユーザー
        int retained3m = countRetainedUsers(date, 3);
        int retained6m = countRetainedUsers(date, 6);
        int retained12m = countRetainedUsers(date, 12);

        saveFunnelSnapshot(date, FunnelStage.REGISTERED, registered);
        saveFunnelSnapshot(date, FunnelStage.TEAM_JOINED, teamJoined);
        saveFunnelSnapshot(date, FunnelStage.MODULE_ACTIVATED, (int) moduleActivated);
        saveFunnelSnapshot(date, FunnelStage.FIRST_PAYMENT, firstPayment);
        saveFunnelSnapshot(date, FunnelStage.SUBSCRIBED, subscribed);
        saveFunnelSnapshot(date, FunnelStage.RETAINED_3M, retained3m);
        saveFunnelSnapshot(date, FunnelStage.RETAINED_6M, retained6m);
        saveFunnelSnapshot(date, FunnelStage.RETAINED_12M, retained12m);
    }

    /**
     * N ヶ月前に登録されたユーザーのうち、現在もアクティブなユーザー数を返す。
     */
    private int countRetainedUsers(LocalDate date, int monthsAgo) {
        LocalDate cohortStart = date.minusMonths(monthsAgo).withDayOfMonth(1);
        LocalDate cohortEnd = cohortStart.plusMonths(1).minusDays(1);
        var userIds = userRepository.findUserIdsCreatedBetween(cohortStart, cohortEnd);
        if (userIds.isEmpty()) {
            return 0;
        }
        return userRepository.countActiveByUserIds(userIds);
    }

    private void saveFunnelSnapshot(LocalDate date, FunnelStage stage, int userCount) {
        AnalyticsFunnelSnapshotEntity entity = AnalyticsFunnelSnapshotEntity.builder()
                .date(date)
                .stage(stage)
                .userCount(userCount)
                .build();
        funnelRepository.save(entity);
    }
}
