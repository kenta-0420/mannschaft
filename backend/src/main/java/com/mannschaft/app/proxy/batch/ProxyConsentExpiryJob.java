package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.proxy.service.ProxyConsentLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 代理入力同意書の有効期限切れ自動失効バッチ（F14.1 Phase 13-β）。
 * 日次 02:00 JST に実行し、effective_until &lt; TODAY の同意書を AUTO_BY_TENURE_END で失効させる。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyConsentExpiryJob {

    private final ProxyConsentLifecycleService lifecycleService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SUCCESSION_PROXY_ENABLED",
            reason = "同じキーで代理入力の入口も閉じるため失効漏れの同意書が行使される経路が無く、再開後に有効期限条件で失効し直せる")
    @BatchEndpoint(name = "proxy-consent-expiry-daily", description = "代理入力同意書の有効期限切れを毎日 02:00 に AUTO_BY_TENURE_END で失効する")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ProxyConsentExpiryJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        log.info("ProxyConsentExpiryJob 開始");
        int count = lifecycleService.expireOutdatedConsents();
        log.info("ProxyConsentExpiryJob 完了: {}件を失効", count);
    }
}
