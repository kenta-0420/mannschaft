package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 領収書再発行プレビューリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReissueReceiptRequest {

    private final BigDecimal amount;

    @Size(max = 500)
    private final String description;

    private final BigDecimal taxRate;

    @Size(max = 50)
    private final String paymentMethodLabel;
}
