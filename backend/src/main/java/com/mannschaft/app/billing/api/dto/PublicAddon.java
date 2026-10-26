package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** 未認証でも参照できる有償アドオン表示情報。 */
@Getter
@Builder
@Schema(name = "PublicAddon", description = "公開アドオン価格")
public class PublicAddon {

    private final String featureKey;
    private final String displayNameKey;
    private final String descriptionKey;

    @Schema(description = "月額の起点（税込）。見積り必須又は未提供時はnull", nullable = true)
    private final PublicMoney startingMonthlyTotal;

    private final List<PublicPriceBand> priceBands;
    private final boolean quoteRequired;
    private final boolean available;
}
