package com.mannschaft.app.knowledgebase.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.knowledgebase.repository.KbPageFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 本人の個人設定を30日後に強消去する。本文・共同業務データは対象外。 */
@Component
@RequiredArgsConstructor
public class KbPageFavoritePurgeEventListener {

    private final KbPageFavoriteRepository kbPageFavoriteRepository;
    private final AccountPurgeCompletionService completionService;

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の個人設定を消去する。停止すると設定が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purgeSettings(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "knowledgebase");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeSettings(userId);
        return true;
    }

    /** 同じ所有domain内の全削除を一つのTXで実行し、途中失敗を伝播させる。 */
    private void purgeSettings(Long userId) {
        kbPageFavoriteRepository.deleteByUserId(userId);
    }
}
