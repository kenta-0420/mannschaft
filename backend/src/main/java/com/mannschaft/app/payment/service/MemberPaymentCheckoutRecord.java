package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;

import java.math.BigDecimal;
import java.util.UUID;

/** Connect checkout 永続化境界用の非 Entity DTO。 */
public record MemberPaymentCheckoutRecord(
        Long id,
        Long userId,
        Long paymentItemId,
        BigDecimal amountPaid,
        String currency,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        Long payerUserId,
        PayerRelationship payerRelationship,
        UUID escrowTransactionId) {

    static MemberPaymentCheckoutRecord fromEntity(MemberPaymentEntity entity) {
        return new MemberPaymentCheckoutRecord(entity.getId(), entity.getUserId(), entity.getPaymentItemId(),
                entity.getAmountPaid(), entity.getCurrency(), entity.getPaymentMethod(), entity.getStatus(),
                entity.getPayerUserId(), entity.getPayerRelationship(), entity.getEscrowTransactionId());
    }

    MemberPaymentEntity toEntity() {
        return MemberPaymentEntity.builder()
                .userId(userId).paymentItemId(paymentItemId).amountPaid(amountPaid).currency(currency)
                .paymentMethod(paymentMethod).status(status).payerUserId(payerUserId)
                .payerRelationship(payerRelationship).escrowTransactionId(escrowTransactionId).build();
    }
}
