package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 備品更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateEquipmentItemRequest {

    @NotBlank
    @Size(max = 200)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 100)
    private final String category;

    @Min(1)
    private final Integer quantity;

    @Size(max = 200)
    private final String storageLocation;

    private final LocalDate purchaseDate;

    private final BigDecimal purchasePrice;

    private final Boolean isConsumable;

    private final String status;
}
