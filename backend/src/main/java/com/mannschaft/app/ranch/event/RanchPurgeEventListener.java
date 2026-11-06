package com.mannschaft.app.ranch.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** account purge commit後にRanch個人行を消し、成功した場合のみ固定domain完了を通知する。 */
@Component
@RequiredArgsConstructor
public class RanchPurgeEventListener {
    private final RanchPurgeService purge;
    private final AccountPurgeCompletionService completion;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会強消去済み本人の牧場行を消す。停止すると個人行が残留する")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purge.purgeUser(userId);
        completion.markDomainSuccess(userId, "ranch");
    }

    /** completionのretry_count/status更新はCOMMON callerが担当する。 */
    public boolean retryPurge(Long userId) {
        purge.purgeUser(userId);
        return true;
    }
}
