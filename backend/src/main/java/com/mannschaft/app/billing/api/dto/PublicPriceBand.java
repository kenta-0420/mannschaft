package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/** 公開価格の人数帯。未提供時は {@code startingMonthlyTotal} を返さない。 */
@Getter
@Builder
@Schema(name = "PublicPriceBand", description = "公開価格の人数帯")
public class PublicPriceBand {

    @Schema(description = "この帯の最小人数", example = "1")
    private final int minMembers;

    @Schema(description = "この帯の最大人数。上限なしはnull", nullable = true, example = "10")
    private final Integer maxMembers;

    @Schema(description = "この帯の月額（税込）。見積り必須又は未提供時はnull", nullable = true)
    private final PublicMoney startingMonthlyTotal;
}
