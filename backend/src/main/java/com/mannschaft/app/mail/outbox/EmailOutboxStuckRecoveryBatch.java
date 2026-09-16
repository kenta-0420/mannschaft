package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F09.18 SENDING 残骸リカバリバッチ (設計書 §15-付記)。
 *
 * <p>Worker が SENDING に遷移させた後、SES 呼び出しの最中に pod クラッシュした場合、
 * 行が SENDING のまま放置される。本バッチは毎時 0 分に
 * {@code updated_at < NOW() - 5min} の SENDING を PENDING に戻す。</p>
 *
 * <p>5 分閾値の根拠: SES sendEmail のタイムアウトを含めても 1 分以上かかるケースは異常。
 * Worker の {@code lockAtMostFor=PT2M} と整合させ、ロックが切れた頃を見計らって回収。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxStuckRecoveryBatch {

    private static final int STUCK_THRESHOLD_MINUTES = 5;

    private final EmailOutboxRepository repository;

    /**
     * 毎時 0 分に SENDING 残骸を PENDING に戻す。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると SENDING のまま残った行が永久に再送されず、送信されないメールが復旧不能なまま取り残される")
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(
            name = "emailOutboxStuckRecovery",
            lockAtMostFor = "PT2H",
            lockAtLeastFor = "PT10S"
    )
    @BatchEndpoint(name = "email-outbox-stuck-recovery",
            description = "5分以上SENDINGのまま滞留したメール送信キューをPENDINGに戻し再送可能にする（毎時0分）")
    @Transactional
    public void recover() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        int recovered = repository.recoverStuckSending(threshold);
        if (recovered > 0) {
            log.warn("Recovered {} stuck SENDING rows in email_outbox (threshold={})",
                    recovered, threshold);
        }
    }
}
