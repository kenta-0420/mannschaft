package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 備品作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateEquipmentRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Min(1)
    private final Integer totalQuantity;

    private final BigDecimal pricePerUse;

    @Min(0)
    private final Integer displayOrder;
}
