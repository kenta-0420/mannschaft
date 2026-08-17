package com.mannschaft.app.returnstayplan.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** F02.11 退会時削除の契約骨格。 */
@Component
@RequiredArgsConstructor
public class ReturnStayPlanLifecycleListener {

    private final ReturnStayPlanService service;

    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        service.deleteAllForOwner(event.getUserId());
    }

    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        service.deleteAllForOwner(event.getUserId());
    }
}
