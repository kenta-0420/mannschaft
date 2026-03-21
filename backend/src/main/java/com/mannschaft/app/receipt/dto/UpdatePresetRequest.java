package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * プリセット更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePresetRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    @Size(max = 500)
    private final String description;

    @NotNull
    private final BigDecimal amount;

    private final BigDecimal taxRate;

    private final String lineItemsJson;

    @Size(max = 50)
    private final String paymentMethodLabel;

    private final Boolean sealStamp;
}
