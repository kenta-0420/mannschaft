package com.mannschaft.app.membership.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 退会時にスコープ別メンバーカレンダー色の個人参照を即時消去する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeMemberCalendarSettingAnonymizationEventListener {

    private final AccountPurgeCompletionService completionService;

    private final ScopeMemberCalendarSettingRepository repository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者のカレンダー設定に個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            repository.deleteByUserId(userId);
            log.info("退会ユーザーのメンバーカレンダー色を削除しました: userId={}", userId);
        } catch (Exception ex) {
            log.warn("退会ユーザーのメンバーカレンダー色削除に失敗しました: userId={}, error={}",
                    userId, ex.getMessage(), ex);
        }
    }

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
                completionService.markDomainSuccess(userId, "membership");
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
        repository.deleteByUserId(userId);
    }
}
