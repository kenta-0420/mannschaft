package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.receipt.ReceiptQueueStatus;
import com.mannschaft.app.receipt.ReceiptScopeType;
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

/**
 * 発行待ちキューエンティティ。Stripe 決済完了時に自動追加される。
 */
@Entity
@Table(name = "receipt_queue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReceiptQueueEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceiptScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long memberPaymentId;

    @Column(nullable = false)
    private Long recipientUserId;

    @Column(length = 500)
    private String suggestedDescription;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal suggestedAmount;

    private Long presetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReceiptQueueStatus status = ReceiptQueueStatus.PENDING;

    private Long processedReceiptId;

    /**
     * キューアイテムを承認して、発行された領収書 ID を設定する。
     */
    public void approve(Long processedReceiptId) {
        this.status = ReceiptQueueStatus.APPROVED;
        this.processedReceiptId = processedReceiptId;
    }

    /**
     * キューアイテムをスキップする。
     */
    public void skip() {
        this.status = ReceiptQueueStatus.SKIPPED;
    }
}
