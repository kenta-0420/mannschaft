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
 * 広告キャンペーンエンティティ。
 */
@Entity
@Table(name = "ad_campaigns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdCampaignEntity extends BaseEntity {

    @Column(nullable = false)
    private Long advertiserOrganizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PricingModel pricingModel;

    private BigDecimal dailyBudget;

    private Integer dailyImpressionLimit;

    private LocalDate startDate;

    private LocalDate endDate;

    public enum CampaignStatus {
        DRAFT, PENDING_REVIEW, ACTIVE, PAUSED, ENDED
    }

    public void pause() {
        this.status = CampaignStatus.PAUSED;
    }
}
