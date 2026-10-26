package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 予約支払いレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BookingPaymentResponse {

    private final Long id;
    private final Long bookingId;
    private final Long payerUserId;
    private final String paymentMethod;
    private final BigDecimal amount;
    private final BigDecimal stripeFee;
    private final BigDecimal platformFee;
    private final BigDecimal platformFeeRate;
    private final BigDecimal netAmount;
    private final String status;
    private final String failedReason;
    private final LocalDateTime paidAt;
    private final LocalDateTime refundedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
