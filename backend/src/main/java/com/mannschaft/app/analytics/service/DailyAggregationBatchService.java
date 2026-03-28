package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.FunnelStage;
import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsFunnelSnapshotEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyAdsRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyModulesRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsFunnelSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// TODO: ShedLock 導入後に @SchedulerLock を有効化する
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final AnalyticsDailyModulesRepository modulesRepository;
    private final AnalyticsDailyAdsRepository adsRepository;
    private final AnalyticsFunnelSnapshotRepository funnelRepository;
    private final AnalyticsAlertService alertService;

    /**
     * 日次集計バッチを実行する。
     * ShedLock による排他制御あり。最大ロック30分。
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    // TODO: @SchedulerLock(name = "dailyAnalyticsAggregation", lockAtMostFor = "30m", lockAtLeastFor = "5m")
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
        // member_payments から revenue_source 別に集計
        // INSERT ... ON DUPLICATE KEY UPDATE の冪等性は
        // Repository の saveAll で既存エントリを上書き（UNIQUE KEY + merge）
        log.debug("収益集計: date={}", date);
        // TODO: member_payments テーブルからの実データ集計
        // 現段階では空データを INSERT する骨格実装
        for (RevenueSource source : RevenueSource.values()) {
            AnalyticsDailyRevenueEntity entity = AnalyticsDailyRevenueEntity.builder()
                    .date(date)
                    .revenueSource(source)
                    .build();
            revenueRepository.save(entity);
        }
    }

    private void aggregateUsers(LocalDate date) {
        log.debug("ユーザー集計: date={}", date);
        // TODO: users + member_subscriptions からの実データ集計
        AnalyticsDailyUsersEntity entity = AnalyticsDailyUsersEntity.builder()
                .date(date)
                .build();
        usersRepository.save(entity);
    }

    private void aggregateModules(LocalDate date) {
        log.debug("モジュール集計: date={}", date);
        // TODO: module_subscriptions + module_definitions からの実データ集計
    }

    private void aggregateAds(LocalDate date) {
        log.debug("広告集計: date={}", date);
        // TODO: ad_impressions + ad_clicks からの実データ集計
        // Phase 9 テーブルが存在しない場合はスキップ
    }

    private void aggregateFunnel(LocalDate date) {
        log.debug("ファネル集計: date={}", date);
        // TODO: users + teams + module_subscriptions + member_payments からの実データ集計
        for (FunnelStage stage : FunnelStage.values()) {
            AnalyticsFunnelSnapshotEntity entity = AnalyticsFunnelSnapshotEntity.builder()
                    .date(date)
                    .stage(stage)
                    .build();
            funnelRepository.save(entity);
        }
    }
}
