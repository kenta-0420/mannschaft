package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.facility.PaymentMethod;
import com.mannschaft.app.facility.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 施設予約支払いエンティティ。
 */
@Entity
@Table(name = "facility_booking_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityBookingPaymentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long bookingId;

    @Column(nullable = false)
    private Long payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentMethod paymentMethod = PaymentMethod.DIRECT;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Column(precision = 10, scale = 0)
    private BigDecimal stripeFee;

    @Column(precision = 10, scale = 0)
    private BigDecimal platformFee;

    @Column(precision = 5, scale = 4)
    private BigDecimal platformFeeRate;

    @Column(precision = 10, scale = 0)
    private BigDecimal netAmount;

    @Column(length = 100)
    private String stripePaymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(length = 500)
    private String failedReason;

    private LocalDateTime paidAt;

    private LocalDateTime refundedAt;

    /**
     * DIRECT支払いを確認する。
     */
    public void confirmDirectPayment() {
        this.status = PaymentStatus.SUCCEEDED;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * 支払いを返金する。
     */
    public void refund() {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = LocalDateTime.now();
    }
}
