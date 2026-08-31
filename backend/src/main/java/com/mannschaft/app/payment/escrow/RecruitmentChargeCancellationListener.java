package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.recruitment.event.RecruitmentCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** F22.1 市: 札の取下げ確定後に、紐づく未 capture の与信を取消す。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentChargeCancellationListener {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final EscrowLifecycleService escrowLifecycleService;

    // 手動・自動取消のトランザクションが commit した後に実行する。
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecruitmentCancelled(RecruitmentCancelledEvent event) {
        if (!event.paymentEnabled()) {
            return;
        }

        var escrows = escrowTransactionRepository.findBySourceKindAndSourceId(
                EscrowSourceKind.RECRUITMENT, event.listingId());
        for (EscrowTransactionEntity escrow : escrows) {
            boolean cancelled = escrowLifecycleService.cancelForRecruitmentCancellation(escrow.getId());
            if (!cancelled && escrow.getStatus() != EscrowStatus.CANCELLED
                    && escrow.getStatus() != EscrowStatus.REFUNDED) {
                log.warn("募集取消に連動できない escrow 状態です: listingId={}, escrowId={}, status={}",
                        event.listingId(), escrow.getId(), escrow.getStatus());
            }
        }
    }
}
