package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1 課金履歴 API の金額（最小通貨単位＋通貨コード）。
 *
 * <p>JPY は最小単位＝円のため {@code amount} はそのまま円。通貨を数値と一緒に返すのは、
 * FE 側で通貨記号や桁区切りを推測させないため（AC-54）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingMoney", description = "F20.1 金額（最小通貨単位）")
public class BillingMoneyResponse {

    @Schema(description = "最小通貨単位の金額", example = "11000")
    private final Long amount;

    @Schema(description = "ISO-4217 通貨コード", example = "JPY")
    private final String currency;
}
