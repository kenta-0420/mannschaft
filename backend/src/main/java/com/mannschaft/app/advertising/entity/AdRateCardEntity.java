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
import java.time.LocalDate;

/**
 * 広告料金カードエンティティ。
 */
@Entity
@Table(name = "ad_rate_cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdRateCardEntity extends BaseEntity {

    @Column(length = 20)
    private String targetPrefecture;

    @Column(length = 30)
    private String targetTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PricingModel pricingModel;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal minDailyBudget = new BigDecimal("500");

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveUntil;

    @Column(nullable = false)
    private Long createdBy;
}
