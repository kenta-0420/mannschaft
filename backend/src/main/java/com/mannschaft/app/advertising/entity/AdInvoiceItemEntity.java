package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.PricingModel;
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

/**
 * 広告請求書明細エンティティ。
 */
@Entity
@Table(name = "ad_invoice_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdInvoiceItemEntity extends BaseEntity {

    @Column(nullable = false)
    private Long invoiceId;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 200)
    private String campaignName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private PricingModel pricingModel;

    @Column(nullable = false)
    @Builder.Default
    private long impressions = 0;

    @Column(nullable = false)
    @Builder.Default
    private long clicks = 0;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;
}
