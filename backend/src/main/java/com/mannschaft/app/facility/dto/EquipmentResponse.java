package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 備品レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EquipmentResponse {

    private final Long id;
    private final Long facilityId;
    private final String name;
    private final String description;
    private final Integer totalQuantity;
    private final BigDecimal pricePerUse;
    private final Boolean isAvailable;
    private final Integer displayOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
