package com.mannschaft.app.advertising.dto;

import java.util.List;

/**
 * 料金シミュレーターレスポンス。
 */
public record RateSimulatorResponse(
        RateSimulatorInput input,
        RateCardInfo rateCard,
        RateSimulatorEstimate estimate,
        List<RateComparisonItem> comparison
) {
}
