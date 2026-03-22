package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 備品更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateEquipmentRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Min(1)
    private final Integer totalQuantity;

    private final BigDecimal pricePerUse;

    private final Boolean isAvailable;

    @Min(0)
    private final Integer displayOrder;
}
