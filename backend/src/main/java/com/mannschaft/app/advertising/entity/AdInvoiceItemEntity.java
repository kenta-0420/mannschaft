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
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 広告請求書明細エンティティ。
 *
 * <p>F09.7 系の従来課金行は {@code campaignId} (BIGINT) を NOT NULL で持つ。
 * F09.17 メッセージ型キャンペーン由来の課金行は {@code messagingCampaignId} (UUIDv7) と
 * {@code channelType} / {@code monthKey} を持ち、{@code campaignId} を NULL のままにする。</p>
 *
 * <p>UNIQUE 制約 {@code (messaging_campaign_id, channel_type, month_key)} で
 * 月次ブリッジバッチの冪等性を確保する。</p>
 */
@Entity
@Table(name = "ad_invoice_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
public class AdInvoiceItemEntity extends BaseEntity {

    @Column(nullable = false)
    private Long invoiceId;

    /**
     * F09.7 由来課金行で必須、F09.17 由来課金行では NULL。
     */
    @Column
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

    /**
     * F09.17 メッセージ型キャンペーン由来の課金行を識別する UUIDv7。
     * F09.7 由来行では NULL。
     */
    @Column(name = "messaging_campaign_id")
    private UUID messagingCampaignId;

    /**
     * F09.17 由来課金行のチャネル種別 (ANNOUNCEMENT / EMAIL / PUSH / BANNER)。
     * F09.7 由来行では NULL。
     */
    @Column(name = "channel_type", length = 20)
    private String channelType;

    /**
     * F09.17 由来課金行の集計対象月 (YYYY-MM)。
     * F09.7 由来行では NULL。
     */
    @Column(name = "month_key", length = 7)
    private String monthKey;
}
