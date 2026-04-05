package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;

/**
 * 料金シミュレーター入力情報。
 */
public record RateSimulatorInput(
        String prefecture,
        String template,
        PricingModel pricingModel,
        Integer impressions,
        Integer clicks,
        Integer days
) {
}
