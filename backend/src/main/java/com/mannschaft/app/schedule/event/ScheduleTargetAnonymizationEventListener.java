package com.mannschaft.app.schedule.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.schedule.repository.ScheduleTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 退会時に予定対象者の個人参照を即時消去する。予定本体と target mode は保持する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleTargetAnonymizationEventListener {

    private final ScheduleTargetRepository scheduleTargetRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者を対象とする予定の宛先情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            scheduleTargetRepository.deleteByUserId(userId);
            log.info("退会ユーザーの予定対象者参照を削除しました: userId={}", userId);
        } catch (Exception ex) {
            log.warn("退会ユーザーの予定対象者参照削除に失敗しました: userId={}, error={}",
                    userId, ex.getMessage(), ex);
        }
    }
}
