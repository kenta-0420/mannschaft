package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * サブリース決済レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubleasePaymentResponse {

    private final Long id;
    private final Long subleaseId;
    private final Long payerUserId;
    private final Long payeeUserId;
    private final BigDecimal amount;
    private final BigDecimal stripeFee;
    private final BigDecimal platformFee;
    private final BigDecimal netAmount;
    private final String billingMonth;
    private final String status;
    private final LocalDateTime paidAt;
    private final LocalDateTime createdAt;
}
