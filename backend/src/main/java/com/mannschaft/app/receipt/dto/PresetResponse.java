package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * プリセットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresetResponse {
    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String description;
    private final BigDecimal amount;
    private final BigDecimal taxRate;
    private final String lineItemsJson;
    private final String paymentMethodLabel;
    private final Boolean sealStamp;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
