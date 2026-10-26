package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 備品レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EquipmentItemResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final String name;
    private final String description;
    private final String category;
    private final Integer quantity;
    private final String status;
    private final Integer availableQuantity;
    private final Integer assignedQuantity;
    private final Boolean isConsumable;
    private final String storageLocation;
    private final LocalDate purchaseDate;
    private final BigDecimal purchasePrice;
    private final String imageUrl;
    private final String qrCode;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
