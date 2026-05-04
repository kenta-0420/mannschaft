package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.proxy.service.ProxyMonthlySummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 代理入力の月次サマリPDF生成バッチ（F14.1 Phase 13-β）。
 * 毎月1日 03:00 JST に実行し、前月分のサマリPDFを生成してS3に保存する。
 * 管理員が印刷・配布して非デジタル住民への月次報告に使用する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyMonthlySummaryBatchJob {

    private final ProxyMonthlySummaryService summaryService;

    /**
     * 毎月1日 03:00 JST に実行する。
     * ShedLock により複数インスタンス環境でも1回だけ実行されることを保証する。
     */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ProxyMonthlySummaryBatchJob", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        log.info("ProxyMonthlySummaryBatchJob 開始: 対象月={}", targetMonth);
        int count = summaryService.generateForMonth(targetMonth);
        log.info("ProxyMonthlySummaryBatchJob 完了: {}件のPDFを生成", count);
    }
}
