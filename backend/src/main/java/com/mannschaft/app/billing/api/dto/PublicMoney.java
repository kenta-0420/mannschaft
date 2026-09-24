package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/** 公開価格で返す、税込額・税抜額・税額のスナップショット。 */
@Getter
@Builder
@Schema(name = "PublicMoney", description = "公開価格のJPY金額と税内訳")
public class PublicMoney {

    @Schema(description = "通貨。公開価格はJPYのみ", example = "JPY")
    private final String currency;

    @Schema(description = "税込金額", example = "1100")
    private final long amountIncludingTax;

    @Schema(description = "税抜金額", example = "1000")
    private final long amountExcludingTax;

    @Schema(description = "税額", example = "100")
    private final long taxAmount;

    @Schema(description = "税名称", nullable = true, example = "消費税")
    private final String taxName;

    @Schema(description = "税率（basis points）", nullable = true, example = "1000")
    private final Integer taxRateBasisPoints;
}
