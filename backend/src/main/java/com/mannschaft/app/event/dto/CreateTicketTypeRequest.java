package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * チケット種別作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTicketTypeRequest {

    @NotBlank
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

    private final Integer sortOrder;
}
