package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支払い記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberPaymentResponse {

    private final Long id;
    private final Long userId;
    private final Long paymentItemId;
    private final BigDecimal amountPaid;
    private final String currency;
    private final String paymentMethod;
    private final String status;
    private final LocalDate validFrom;
    private final LocalDate validUntil;
    private final LocalDateTime paidAt;
    private final String note;
    private final String stripeRefundId;
    private final String stripeReceiptUrl;
    private final LocalDateTime refundedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
