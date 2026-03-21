package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
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
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 発行プリセットエンティティ。よく使う金額・但し書き・税率を保存する。
 */
@Entity
@Table(name = "receipt_presets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReceiptPresetEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceiptScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal taxRate = new BigDecimal("10.00");

    @Column(columnDefinition = "JSON")
    private String lineItemsJson;

    @Column(length = 50)
    private String paymentMethodLabel;

    @Column(nullable = false)
    @Builder.Default
    private Boolean sealStamp = true;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * プリセットを更新する。
     */
    public void update(String name, String description, BigDecimal amount, BigDecimal taxRate,
                       String lineItemsJson, String paymentMethodLabel, Boolean sealStamp) {
        this.name = name;
        this.description = description;
        this.amount = amount;
        this.taxRate = taxRate;
        this.lineItemsJson = lineItemsJson;
        this.paymentMethodLabel = paymentMethodLabel;
        this.sealStamp = sealStamp;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
