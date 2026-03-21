package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 支払い項目更新リクエストDTO。変更するフィールドのみ指定する。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePaymentItemRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @DecimalMin(value = "0.01")
    private final BigDecimal amount;

    @Size(min = 3, max = 3)
    private final String currency;

    private final Boolean isActive;

    private final Short gracePeriodDays;

    private final Short displayOrder;

    @Size(max = 100)
    private final String stripePriceId;

    /** type 変更を試みた場合に検出するため受け取る（変更時は 422 を返す）。 */
    private final String type;
}
