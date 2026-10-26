package com.mannschaft.app.advertising.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 広告主ダッシュボード概要レスポンス（Phase 1 基本版）。
 * <p>
 * inner static record を含むため class で定義する。
 */
@Getter
@RequiredArgsConstructor
public class AdvertiserOverviewResponse {

    private final Period period;
    private final int totalCampaigns;
    private final int activeCampaigns;
    private final long totalImpressions;
    private final long totalClicks;
    private final BigDecimal avgCtr;
    private final BigDecimal totalCost;
    private final BigDecimal monthlyBudgetUsedPct;
    private final BigDecimal creditLimit;
    private final List<CampaignSummary> campaigns;

    /**
     * 集計期間。
     */
    public record Period(
            LocalDate from,
            LocalDate to
    ) {
    }

    /**
     * キャンペーンサマリ。
     */
    public record CampaignSummary(
            Long campaignId,
            String campaignName,
            String status,
            long impressions,
            long clicks,
            BigDecimal ctr,
            BigDecimal cost
    ) {
    }
}
