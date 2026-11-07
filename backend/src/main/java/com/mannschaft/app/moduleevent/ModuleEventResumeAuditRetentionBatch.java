package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 再開監査の保持期限を日次で整理する入口。
 * 削除はM privacy APIの独立TXへ委譲し、actor stateは保持する。
 */
@Slf4j
@Component
public class ModuleEventResumeAuditRetentionBatch {
    public static final String BATCH_NAME = "module-event-resume-audit-retention-daily";
    public static final String LOCK_NAME = "moduleEventResumeAuditRetentionBatch";
    private final ModuleEventResumeAuditPrivacyApi privacyApi;

    public ModuleEventResumeAuditRetentionBatch(ModuleEventResumeAuditPrivacyApi privacyApi) {
        this.privacyApi = privacyApi;
    }

    /**
     * UTC 03:00の日次整理。created_at索引を持つ単一DELETEのquery予算300秒と、
     * 本番Hikari接続取得3秒、取消・rollback/commit・解放の保守的余裕1497秒からleaseを30分と推定する。
     * 余裕は実測最大値ではなく、通信障害を含めた完走の絶対期限も保証しない。
     * backlog最大量と所要時間は未測定であり、timeoutが続く場合は容量と競合を再評価する。
     */
    @BatchEndpoint(name = BATCH_NAME, description = "UTC暦年2年より古い再開監査を毎日03:00 UTCに整理する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "保存済み監査の保持期限を守る処理であり、機能停止中に止めると不要な個人データの保持が続くため常時実行する")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional(propagation = Propagation.NEVER)
    public Integer deleteExpiredAudits() {
        LockAssert.assertLocked();
        int deleted = privacyApi.deleteExpired(Instant.now());
        log.info("再開監査の日次保持削除が完了しました: deleted={}", deleted);
        return deleted;
    }
}
