package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.SubleasePaymentStatus;
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
 * サブリース決済エンティティ。
 */
@Entity
@Table(name = "parking_sublease_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSubleasePaymentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long subleaseId;

    @Column(nullable = false)
    private Long payerUserId;

    @Column(nullable = false)
    private Long payeeUserId;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal stripeFee;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal platformFee;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal platformFeeRate;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 7)
    private String billingMonth;

    @Column(length = 100)
    private String stripePaymentIntentId;

    @Column(length = 100)
    private String stripeTransferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubleasePaymentStatus status = SubleasePaymentStatus.PENDING;

    @Column(length = 500)
    private String failedReason;

    private LocalDateTime paidAt;

    /**
     * 決済成功を記録する。
     */
    public void markSucceeded(String stripePaymentIntentId, String stripeTransferId) {
        this.status = SubleasePaymentStatus.SUCCEEDED;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.stripeTransferId = stripeTransferId;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * 決済失敗を記録する。
     */
    public void markFailed(String failedReason) {
        this.status = SubleasePaymentStatus.FAILED;
        this.failedReason = failedReason;
    }
}
