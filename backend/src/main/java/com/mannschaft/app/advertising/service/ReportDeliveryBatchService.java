package com.mannschaft.app.advertising.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.repository.AdReportScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDeliveryBatchService {

    private final AdReportScheduleRepository adReportScheduleRepository;
    private final ReportDeliveryRunner reportDeliveryRunner;

    /**
     * 週次レポート配信バッチ。毎週月曜 AM 9:00 (JST)。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_PROMOTION_ENABLED",
            reason = "止まるのは広告主向け週次レポート配信のみで DB は書き換わらず、広告機能を閉じている間は配信先の広告主が存在しない")
    @BatchEndpoint(name = "advertising-report-delivery-weekly", description = "広告主向け週次レポートを毎週月曜 09:00 に配信する")
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Tokyo")
    @SchedulerLock(name = "reportDeliveryWeekly", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deliverWeeklyReports() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)).minusWeeks(1);
        LocalDate to = from.plusDays(6);
        deliverReports(ReportFrequency.WEEKLY, from, to);
    }

    /**
     * 月次レポート配信バッチ。毎月1日 AM 9:00 (JST)。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_PROMOTION_ENABLED",
            reason = "止まるのは広告主向け月次レポート配信のみで DB は書き換わらず、広告機能を閉じている間は配信先の広告主が存在しない")
    @BatchEndpoint(name = "advertising-report-delivery-monthly", description = "広告主向け月次レポートを毎月 1 日 09:00 に配信する")
    @Scheduled(cron = "0 0 9 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "reportDeliveryMonthly", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deliverMonthlyReports() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        LocalDate from = lastMonth.atDay(1);
        LocalDate to = lastMonth.atEndOfMonth();
        deliverReports(ReportFrequency.MONTHLY, from, to);
    }

    /**
     * 対象スケジュールを走査し、1件ずつ {@link ReportDeliveryRunner} に委譲する。
     *
     * <p>1 件の配信は {@code REQUIRES_NEW} の独立トランザクションで実行する
     * （バッチ失敗時のリトライ安全性を確保するため）。本メソッド自体は対象一覧の
     * 読み取りのみのため {@code @Transactional} を付けない。
     */
    private void deliverReports(ReportFrequency frequency, LocalDate from, LocalDate to) {
        log.info("レポート配信バッチ開始: frequency={}, period={} ~ {}", frequency, from, to);

        List<AdReportScheduleEntity> schedules =
                adReportScheduleRepository.findByEnabledTrueAndFrequency(frequency);

        int successCount = 0;
        int errorCount = 0;

        for (AdReportScheduleEntity schedule : schedules) {
            try {
                // 別トランザクション（REQUIRES_NEW）で実行するため、このループで
                // 取得済みのエンティティは使わず ID で再フェッチさせる。
                reportDeliveryRunner.deliverOne(schedule.getId(), from, to);
                successCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("レポート配信エラー: scheduleId={}, error={}", schedule.getId(), e.getMessage(), e);
            }
        }

        log.info("レポート配信バッチ完了: frequency={}, 成功={}, エラー={}", frequency, successCount, errorCount);
    }
}
