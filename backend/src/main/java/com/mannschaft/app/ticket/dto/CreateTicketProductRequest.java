package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 回数券商品作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTicketProductRequest {

    @NotBlank
    @Size(max = 200)
    private final String name;

    @Size(max = 1000)
    private final String description;

    @NotNull
    @Positive
    private final Integer totalTickets;

    @NotNull
    @Positive
    private final Integer price;

    private final BigDecimal taxRate;

    @Positive
    private final Integer validityDays;

    private final Boolean isOnlinePurchasable;

    @Size(max = 500)
    private final String imageUrl;
}
