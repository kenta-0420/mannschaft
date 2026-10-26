package com.mannschaft.app.budget.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 予算取引が取消（反転仕訳）された際に発行されるドメインイベント。
 */
@Getter
public class BudgetTransactionReversedEvent extends BaseEvent {

    private final Long originalTransactionId;
    private final Long reversalTransactionId;
    private final Long fiscalYearId;
    private final BigDecimal amount;
    private final Long scopeId;
    private final String scopeType;
    private final Long reversedByUserId;

    public BudgetTransactionReversedEvent(Long originalTransactionId, Long reversalTransactionId,
                                           Long fiscalYearId, BigDecimal amount,
                                           Long scopeId, String scopeType, Long reversedByUserId) {
        super();
        this.originalTransactionId = originalTransactionId;
        this.reversalTransactionId = reversalTransactionId;
        this.fiscalYearId = fiscalYearId;
        this.amount = amount;
        this.scopeId = scopeId;
        this.scopeType = scopeType;
        this.reversedByUserId = reversedByUserId;
    }
}
