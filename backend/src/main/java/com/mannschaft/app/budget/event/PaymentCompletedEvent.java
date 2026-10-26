package com.mannschaft.app.budget.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 支払い完了イベント。決済機能（F08.2）から発行される想定。
 */
@Getter
public class PaymentCompletedEvent extends BaseEvent {

    private final Long paymentId;
    private final Long scopeId;
    private final String scopeType;
    private final BigDecimal amount;
    private final String description;
    private final String paymentMethod;

    public PaymentCompletedEvent(Long paymentId, Long scopeId, String scopeType,
                                  BigDecimal amount, String description, String paymentMethod) {
        super();
        this.paymentId = paymentId;
        this.scopeId = scopeId;
        this.scopeType = scopeType;
        this.amount = amount;
        this.description = description;
        this.paymentMethod = paymentMethod;
    }
}
