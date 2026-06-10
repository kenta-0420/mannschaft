package com.mannschaft.app.payment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 支払い項目作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePaymentItemRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @NotNull
    private final String type;

    @NotNull
    @DecimalMin(value = "0.01")
    private final BigDecimal amount;

    @Size(min = 3, max = 3)
    private final String currency;

    private final Boolean isActive;

    private final Short gracePeriodDays;

    private final Short displayOrder;

    @Size(max = 100)
    private final String stripePriceId;

    /** F08.9 P6: 期別有効開始日（type=TERM のみ使用）。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate termStartsOn;

    /** F08.9 P6: 期別有効終了日（type=TERM のみ使用・TERM 型では必須）。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate termEndsOn;
}
