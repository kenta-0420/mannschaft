package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 再開監査の保持期限を日次で整理する入口。
 * 削除はM privacy APIの独立TXへ委譲する。現在は試練用の公開骨格で、業務本体は未実装。
 */
@Component
public class ModuleEventResumeAuditRetentionBatch {
    public static final String BATCH_NAME = "module-event-resume-audit-retention-daily";
    public static final String LOCK_NAME = "moduleEventResumeAuditRetentionBatch";
    private final ModuleEventResumeAuditPrivacyApi privacyApi;

    public ModuleEventResumeAuditRetentionBatch(ModuleEventResumeAuditPrivacyApi privacyApi) {
        this.privacyApi = privacyApi;
    }

    /**
     * UTC 03:00の日次整理。PT30Mは未確定の試練surface値で、完走時間の保証ではない。
     * green化前に既存DB/TX/queryの有限timeoutとcreated_at索引による単一DELETEを確認し、
     * 最悪処理時間に基づくlease根拠を確定する。業務本体はまだ実装しない。
     */
    @BatchEndpoint(name = BATCH_NAME, description = "UTC暦年2年より古い再開監査を毎日03:00 UTCに整理する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "保存済み監査の保持期限を守る処理であり、機能停止中に止めると不要な個人データの保持が続くため常時実行する")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional(propagation = Propagation.NEVER)
    public Integer deleteExpiredAudits() {
        LockAssert.assertLocked();
        throw new UnsupportedOperationException("未実装");
    }
}
