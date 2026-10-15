package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * サブリース作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSubleaseRequest {

    @NotNull
    private final Long spaceId;

    @NotBlank
    @Size(max = 100)
    private final String title;

    private final String description;

    @NotNull
    private final BigDecimal pricePerMonth;

    private final String paymentMethod;

    @NotNull
    private final LocalDate availableFrom;

    private final LocalDate availableTo;
}
