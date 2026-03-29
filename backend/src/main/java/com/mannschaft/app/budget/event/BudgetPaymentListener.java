package com.mannschaft.app.budget.event;

import com.mannschaft.app.budget.service.BudgetTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 決済完了イベントリスナー。
 * PaymentCompletedEventを購読し、自動記帳（収入）を実行する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetPaymentListener {

    private final BudgetTransactionService transactionService;

    /**
     * 決済完了イベントを処理し、収入を自動記帳する。
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        try {
            log.info("決済完了による自動記帳を処理します: paymentId={}, scopeId={}, amount={}",
                    event.getPaymentId(), event.getScopeId(), event.getAmount());
            transactionService.autoRecordPaymentIncome(
                    event.getScopeId(),
                    event.getScopeType(),
                    event.getAmount(),
                    event.getDescription(),
                    event.getPaymentMethod());
        } catch (Exception e) {
            log.error("決済完了の自動記帳に失敗しました: paymentId={}", event.getPaymentId(), e);
        }
    }
}
