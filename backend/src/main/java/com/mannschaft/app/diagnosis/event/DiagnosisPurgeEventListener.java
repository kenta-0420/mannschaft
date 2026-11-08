package com.mannschaft.app.diagnosis.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.diagnosis.service.DiagnosisPurgeService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.event.DiagnosisPurgeRetryRequestedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 診断自身の削除コミット後だけ完了を記録する。失敗は既存未完了の再試行へ残す。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisPurgeEventListener {
    private final DiagnosisPurgeService purge;
    private final AccountPurgeCompletionService completion;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "診断回答と出生派生結果の完全削除は公開機能の停止状態にかかわらず必要")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        purgeAndComplete(event.getUserId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "診断の完全削除再試行は公開機能の停止状態にかかわらず必要")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRetryRequested(DiagnosisPurgeRetryRequestedEvent event) {
        purgeAndComplete(event.getUserId());
    }

    private void purgeAndComplete(Long userId) {
        try {
            // この入口は非TX。自身の独立削除proxyが正常終了した後にだけ別TXで完了を記録する。
            purge.purgeUser(userId);
            completion.markDomainSuccess(userId, "diagnosis");
        } catch (RuntimeException failure) {
            // 本文・例外message・cause・emailHashを保存しない。成功記録も付けない。
            log.warn("診断完全削除未完了: userId={}, failureClass={}",
                    userId, failure.getClass().getSimpleName());
        }
    }
}
