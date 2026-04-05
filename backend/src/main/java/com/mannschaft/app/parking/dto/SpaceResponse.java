package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 区画レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SpaceResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String spaceNumber;
    private final String spaceType;
    private final String spaceTypeLabel;
    private final BigDecimal pricePerMonth;
    private final String status;
    private final String floor;
    private final String notes;
    private final String applicationStatus;
    private final String allocationMethod;
    private final LocalDateTime applicationDeadline;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
