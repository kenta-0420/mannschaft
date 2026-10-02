package com.mannschaft.app.budget.event;

import com.mannschaft.app.budget.service.BudgetTransactionService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると決済済みなのに収入の自動記帳が行われず帳簿が合わなくなる。上流の payment は別ドメインであり一緒には閉じない")
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
                    event.getPaymentMethod(),
                    event.getPaymentId());
        } catch (Exception e) {
            log.error("決済完了の自動記帳に失敗しました: paymentId={}", event.getPaymentId(), e);
        }
    }
}
