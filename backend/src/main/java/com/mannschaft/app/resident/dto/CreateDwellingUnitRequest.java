package com.mannschaft.app.resident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 居室作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateDwellingUnitRequest {

    @NotBlank
    @Size(max = 50)
    private final String unitNumber;

    private final Short floor;

    private final BigDecimal areaSqm;

    @Size(max = 20)
    private final String layout;

    @Size(max = 20)
    private final String unitType;

    private final String notes;
}
