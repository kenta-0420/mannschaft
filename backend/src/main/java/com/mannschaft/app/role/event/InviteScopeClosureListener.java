package com.mannschaft.app.role.event;

import com.mannschaft.app.common.event.PersonalInviteScopeClosedEvent;
import com.mannschaft.app.role.service.InviteTokenClosureCleanupRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 速い経路。親commit後にrole専属REQUIRES_NEWへ値だけを渡し、失敗は回復batchへ残す。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InviteScopeClosureListener {
    private final InviteTokenClosureCleanupRunner cleanup;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(PersonalInviteScopeClosedEvent event) {
        try {
            cleanup.revokeBeforeGeneration(event.getScopeType(), event.getScopeId(), event.getClosureGeneration());
        } catch (RuntimeException failure) {
            log.error("個人招待の閉鎖失効に失敗（回復batchで再実行）: scopeType={}, scopeId={}, generation={}",
                    event.getScopeType(), event.getScopeId(), event.getClosureGeneration(), failure);
        }
    }
}
