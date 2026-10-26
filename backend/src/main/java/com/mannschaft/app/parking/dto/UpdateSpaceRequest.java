package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 区画更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSpaceRequest {

    @NotBlank
    @Size(max = 20)
    private final String spaceNumber;

    @NotNull
    private final String spaceType;

    @Size(max = 50)
    private final String spaceTypeLabel;

    private final BigDecimal pricePerMonth;

    @Size(max = 10)
    private final String floor;

    @Size(max = 500)
    private final String notes;
}
