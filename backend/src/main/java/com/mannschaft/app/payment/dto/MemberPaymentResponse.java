package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 支払い記録レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class MemberPaymentResponse {

    Long id;
    Long userId;
    String userName;
    Long paymentItemId;
    String paymentMethod;
    PaymentMoneyDto money;
    PaymentStatusDto statusInfo;
    PaymentRefundDto refund;
    PaymentAuditDto audit;

    public record PaymentMoneyDto(BigDecimal amountPaid, String currency) {}
    public record PaymentStatusDto(String status, LocalDate validFrom, LocalDate validUntil, LocalDateTime paidAt) {}
    public record PaymentRefundDto(String stripeRefundId, String stripeReceiptUrl, LocalDateTime refundedAt) {}
    public record PaymentAuditDto(String note, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
