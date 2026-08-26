package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SUCCESSION_PROXY_ENABLED",
            reason = "サマリ PDF は代理入力記録から再生成できる派生成果物で、BatchEndpoint 経由の手動再実行経路も持つ。元の記録は保持ジョブ側が守る")
    @BatchEndpoint(name = "proxy-monthly-summary-pdf-generate", description = "代理入力の前月サマリ PDF を毎月 1 日 03:00 に生成して S3 保存する")
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ProxyMonthlySummaryBatchJob", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        log.info("ProxyMonthlySummaryBatchJob 開始: 対象月={}", targetMonth);
        int count = summaryService.generateForMonth(targetMonth);
        log.info("ProxyMonthlySummaryBatchJob 完了: {}件のPDFを生成", count);
    }
}
