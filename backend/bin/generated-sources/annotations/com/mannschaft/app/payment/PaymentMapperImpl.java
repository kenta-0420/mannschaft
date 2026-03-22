package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PaymentMapperImpl implements PaymentMapper {

    @Override
    public PaymentItemResponse toPaymentItemResponse(PaymentItemEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String description = null;
        BigDecimal amount = null;
        String currency = null;
        String stripeProductId = null;
        String stripePriceId = null;
        Boolean isActive = null;
        Short displayOrder = null;
        Short gracePeriodDays = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        description = entity.getDescription();
        amount = entity.getAmount();
        currency = entity.getCurrency();
        stripeProductId = entity.getStripeProductId();
        stripePriceId = entity.getStripePriceId();
        isActive = entity.getIsActive();
        displayOrder = entity.getDisplayOrder();
        gracePeriodDays = entity.getGracePeriodDays();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String type = entity.getType().name();

        PaymentItemResponse paymentItemResponse = new PaymentItemResponse( id, name, description, type, amount, currency, stripeProductId, stripePriceId, isActive, displayOrder, gracePeriodDays, createdAt, updatedAt );

        return paymentItemResponse;
    }

    @Override
    public List<PaymentItemResponse> toPaymentItemResponseList(List<PaymentItemEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PaymentItemResponse> list = new ArrayList<PaymentItemResponse>( entities.size() );
        for ( PaymentItemEntity paymentItemEntity : entities ) {
            list.add( toPaymentItemResponse( paymentItemEntity ) );
        }

        return list;
    }

    @Override
    public MemberPaymentResponse toMemberPaymentResponse(MemberPaymentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long paymentItemId = null;
        BigDecimal amountPaid = null;
        String currency = null;
        LocalDate validFrom = null;
        LocalDate validUntil = null;
        LocalDateTime paidAt = null;
        String note = null;
        String stripeRefundId = null;
        String stripeReceiptUrl = null;
        LocalDateTime refundedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        paymentItemId = entity.getPaymentItemId();
        amountPaid = entity.getAmountPaid();
        currency = entity.getCurrency();
        validFrom = entity.getValidFrom();
        validUntil = entity.getValidUntil();
        paidAt = entity.getPaidAt();
        note = entity.getNote();
        stripeRefundId = entity.getStripeRefundId();
        stripeReceiptUrl = entity.getStripeReceiptUrl();
        refundedAt = entity.getRefundedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String paymentMethod = entity.getPaymentMethod().name();
        String status = entity.getStatus().name();

        MemberPaymentResponse memberPaymentResponse = new MemberPaymentResponse( id, userId, paymentItemId, amountPaid, currency, paymentMethod, status, validFrom, validUntil, paidAt, note, stripeRefundId, stripeReceiptUrl, refundedAt, createdAt, updatedAt );

        return memberPaymentResponse;
    }

    @Override
    public List<MemberPaymentResponse> toMemberPaymentResponseList(List<MemberPaymentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MemberPaymentResponse> list = new ArrayList<MemberPaymentResponse>( entities.size() );
        for ( MemberPaymentEntity memberPaymentEntity : entities ) {
            list.add( toMemberPaymentResponse( memberPaymentEntity ) );
        }

        return list;
    }
}
