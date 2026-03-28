package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.dto.RateCardInfo;
import com.mannschaft.app.advertising.dto.RateComparisonItem;
import com.mannschaft.app.advertising.dto.RateSimulatorEstimate;
import com.mannschaft.app.advertising.dto.RateSimulatorInput;
import com.mannschaft.app.advertising.dto.RateSimulatorResponse;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 料金シミュレーターサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RateSimulatorService {

    private final AdRateCardService adRateCardService;
    private final AdvertisingMapper advertisingMapper;

    @Value("${mannschaft.advertising.tax-rate:10.00}")
    private BigDecimal taxRate;

    private static final int MAX_COMPARISON_ITEMS = 5;

    /**
     * 料金シミュレーションを実行する。
     */
    public RateSimulatorResponse simulate(String prefecture, String template,
                                          PricingModel pricingModel,
                                          Integer impressions, Integer clicks,
                                          Integer days) {
        // 該当料金を取得
        AdRateCardEntity rateCard = adRateCardService.matchRate(
                prefecture, template, pricingModel, LocalDate.now());

        BigDecimal unitPrice = rateCard.getUnitPrice();

        // コスト計算
        BigDecimal totalCost = calculateTotalCost(pricingModel, unitPrice, impressions, clicks);
        BigDecimal taxAmount = totalCost.multiply(taxRate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        BigDecimal totalWithTax = totalCost.add(taxAmount);
        BigDecimal dailyCost = (days != null && days > 0)
                ? totalCost.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 日次インプレッション・クリック推定
        Integer dailyImpressions = (impressions != null && days != null && days > 0)
                ? impressions / days : null;
        Integer estimatedClicks = (pricingModel == PricingModel.CPC) ? clicks : null;
        BigDecimal estimatedCtr = null; // Phase 1 では CTR 推定なし

        // 入力情報
        RateSimulatorInput input = new RateSimulatorInput(
                prefecture, template, pricingModel, impressions, clicks, days);

        // 料金カード情報
        String unitLabel = (pricingModel == PricingModel.CPM) ? "円/1,000imp" : "円/click";
        RateCardInfo rateCardInfo = new RateCardInfo(
                unitPrice, unitLabel, rateCard.getMinDailyBudget(), rateCard.getEffectiveFrom());

        // 見積もり結果
        RateSimulatorEstimate estimate = new RateSimulatorEstimate(
                totalCost, taxAmount, totalWithTax, dailyCost,
                dailyImpressions, estimatedClicks, estimatedCtr,
                null, null, null  // estimatedReach は Phase 1 では null（AdSegmentService 連携は後で）
        );

        // 他の料金との比較（最大5件）
        List<RateComparisonItem> comparison = buildComparison(
                pricingModel, impressions, clicks);

        return new RateSimulatorResponse(input, rateCardInfo, estimate, comparison);
    }

    private BigDecimal calculateTotalCost(PricingModel pricingModel, BigDecimal unitPrice,
                                          Integer impressions, Integer clicks) {
        return switch (pricingModel) {
            case CPM -> {
                int imp = (impressions != null) ? impressions : 0;
                yield BigDecimal.valueOf(imp)
                        .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP)
                        .multiply(unitPrice)
                        .setScale(2, RoundingMode.HALF_UP);
            }
            case CPC -> {
                int clk = (clicks != null) ? clicks : 0;
                yield BigDecimal.valueOf(clk).multiply(unitPrice);
            }
        };
    }

    private List<RateComparisonItem> buildComparison(PricingModel pricingModel,
                                                     Integer impressions, Integer clicks) {
        List<PublicRateCardResponse> currentRates =
                adRateCardService.findCurrentRateCards(pricingModel, null);

        return currentRates.stream()
                .limit(MAX_COMPARISON_ITEMS)
                .map(rate -> {
                    BigDecimal compCost = calculateTotalCost(
                            pricingModel, rate.unitPrice(), impressions, clicks);
                    return new RateComparisonItem(
                            rate.targetPrefecture(),
                            rate.targetTemplate(),
                            rate.unitPrice(),
                            compCost,
                            rate.label()
                    );
                })
                .toList();
    }
}
