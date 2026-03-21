package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * チケット種別更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTicketTypeRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final BigDecimal price;

    @Size(max = 3)
    private final String currency;

    private final Integer maxQuantity;

    @Size(max = 30)
    private final String minRegistrationRole;

    private final Boolean isActive;

    private final Integer sortOrder;
}
