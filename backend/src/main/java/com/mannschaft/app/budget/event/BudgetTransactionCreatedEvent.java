package com.mannschaft.app.budget.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 予算取引が作成された際に発行されるドメインイベント。
 */
@Getter
public class BudgetTransactionCreatedEvent extends BaseEvent {

    private final Long transactionId;
    private final Long fiscalYearId;
    private final Long categoryId;
    private final BigDecimal amount;
    private final String transactionType;
    private final Long scopeId;
    private final String scopeType;
    private final Long createdByUserId;

    public BudgetTransactionCreatedEvent(Long transactionId, Long fiscalYearId, Long categoryId,
                                          BigDecimal amount, String transactionType,
                                          Long scopeId, String scopeType, Long createdByUserId) {
        super();
        this.transactionId = transactionId;
        this.fiscalYearId = fiscalYearId;
        this.categoryId = categoryId;
        this.amount = amount;
        this.transactionType = transactionType;
        this.scopeId = scopeId;
        this.scopeType = scopeType;
        this.createdByUserId = createdByUserId;
    }
}
