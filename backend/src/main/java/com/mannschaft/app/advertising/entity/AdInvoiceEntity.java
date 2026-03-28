package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.common.BaseEntity;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 広告請求書エンティティ。
 */
@Entity
@Table(name = "ad_invoices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdInvoiceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long advertiserAccountId;

    @Column(nullable = false, length = 20)
    private String invoiceNumber;

    @Column(nullable = false)
    private LocalDate invoiceMonth;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal taxRate = new BigDecimal("10.00");

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalWithTax = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(length = 50)
    private String stripeInvoiceId;

    private LocalDateTime issuedAt;

    private LocalDateTime paidAt;

    private LocalDate dueDate;

    @Column(length = 500)
    private String note;

    /**
     * 請求書を発行する（DRAFT → ISSUED）。
     */
    public void issue() {
        if (this.status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("発行はDRAFT状態の請求書のみ可能です");
        }
        this.status = InvoiceStatus.ISSUED;
        this.issuedAt = LocalDateTime.now();
    }

    /**
     * 支払済みにする（ISSUED/OVERDUE → PAID）。
     */
    public void markPaid(LocalDateTime paidAt, String note) {
        if (this.status != InvoiceStatus.ISSUED && this.status != InvoiceStatus.OVERDUE) {
            throw new IllegalStateException("支払済みへの変更はISSUEDまたはOVERDUE状態の請求書のみ可能です");
        }
        this.status = InvoiceStatus.PAID;
        this.paidAt = paidAt;
        this.note = note;
    }

    /**
     * 期限超過にする（ISSUED → OVERDUE）。
     */
    public void markOverdue() {
        if (this.status != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("期限超過への変更はISSUED状態の請求書のみ可能です");
        }
        this.status = InvoiceStatus.OVERDUE;
    }

    /**
     * 合計額を更新する。
     */
    public void updateTotals(BigDecimal totalAmount, BigDecimal taxAmount, BigDecimal totalWithTax) {
        this.totalAmount = totalAmount;
        this.taxAmount = taxAmount;
        this.totalWithTax = totalWithTax;
    }

    /**
     * 支払期限を設定する。
     */
    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }
}
