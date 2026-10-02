package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.repository.AdReportScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * レポート配信バッチ用の 1 件配信 REQUIRES_NEW 実行 Bean（CMP-035）。
 *
 * <p>{@link ReportDeliveryBatchService#deliverWeeklyReports()} /
 * {@link ReportDeliveryBatchService#deliverMonthlyReports()} からループで呼ばれる。
 * バッチ失敗時のリトライ安全性を確保するため、1 件の配信 = 1 独立トランザクションとする必要があり、
 * 独立した Bean に切り出し {@link Propagation#REQUIRES_NEW} を付与する
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。
 */
@Component
@RequiredArgsConstructor
class ReportDeliveryRunner {

    private final AdReportScheduleRepository adReportScheduleRepository;
    private final ReportSingleDeliveryService singleDeliveryService;

    /**
     * 指定スケジュール1件分のレポート配信を独立トランザクションで実行し、最終送信日時を更新する。
     *
     * @param scheduleId スケジュール ID
     * @param from       集計期間の開始日
     * @param to         集計期間の終了日
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverOne(Long scheduleId, LocalDate from, LocalDate to) {
        AdReportScheduleEntity schedule = adReportScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            return;
        }
        singleDeliveryService.deliverSingleReport(schedule, from, to);
        schedule.updateLastSentAt();
        adReportScheduleRepository.save(schedule);
    }
}
