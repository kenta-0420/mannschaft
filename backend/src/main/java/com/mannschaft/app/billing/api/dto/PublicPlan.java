package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** 未認証でも参照できるプラン表示情報。Stripe参照やscope固有情報は含めない。 */
@Getter
@Builder
@Schema(name = "PublicPlan", description = "公開プラン価格")
public class PublicPlan {

    private final String planKey;
    private final String displayNameKey;
    private final String descriptionKey;

    @Schema(description = "月額の起点（税込）。見積り必須又は未提供時はnull", nullable = true)
    private final PublicMoney startingMonthlyTotal;

    private final List<PublicPriceBand> priceBands;
    private final boolean quoteRequired;
    private final boolean available;
    private final List<String> featureKeys;
}
