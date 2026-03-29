package com.mannschaft.app.budget.event;

import com.mannschaft.app.budget.service.BudgetTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ワークフロー承認/却下イベントリスナー。
 * source_type='BUDGET_EXPENSE'の場合に予算取引の承認/却下を実行する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetWorkflowListener {

    private static final String BUDGET_EXPENSE_SOURCE_TYPE = "BUDGET_EXPENSE";

    private final BudgetTransactionService transactionService;

    /**
     * ワークフロー承認イベントを処理する。
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWorkflowApproved(WorkflowApprovedEvent event) {
        if (!BUDGET_EXPENSE_SOURCE_TYPE.equals(event.getSourceType())) {
            return;
        }

        try {
            log.info("予算取引の承認を処理します: transactionId={}, approvedBy={}",
                    event.getSourceId(), event.getApprovedByUserId());
            transactionService.approveTransaction(event.getSourceId(), event.getApprovedByUserId());
        } catch (Exception e) {
            log.error("予算取引の承認処理に失敗しました: transactionId={}", event.getSourceId(), e);
        }
    }

    /**
     * ワークフロー却下イベントを処理する。
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWorkflowRejected(WorkflowRejectedEvent event) {
        if (!BUDGET_EXPENSE_SOURCE_TYPE.equals(event.getSourceType())) {
            return;
        }

        try {
            log.info("予算取引の却下を処理します: transactionId={}, rejectedBy={}",
                    event.getSourceId(), event.getRejectedByUserId());
            transactionService.rejectTransaction(event.getSourceId(), event.getRejectedByUserId());
        } catch (Exception e) {
            log.error("予算取引の却下処理に失敗しました: transactionId={}", event.getSourceId(), e);
        }
    }
}
