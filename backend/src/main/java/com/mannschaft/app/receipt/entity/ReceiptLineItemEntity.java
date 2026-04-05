package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 領収書の税率別明細行エンティティ。複数税率（標準10% + 軽減8%等）対応。
 */
@Entity
@Table(name = "receipt_line_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReceiptLineItemEntity extends BaseEntity {

    @Column(nullable = false)
    private Long receiptId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal taxAmount;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amountExclTax;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
