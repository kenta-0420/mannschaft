package com.mannschaft.app.proxy.batch;

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

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ProxyConsentExpiryJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        log.info("ProxyConsentExpiryJob 開始");
        int count = lifecycleService.expireOutdatedConsents();
        log.info("ProxyConsentExpiryJob 完了: {}件を失効", count);
    }
}
