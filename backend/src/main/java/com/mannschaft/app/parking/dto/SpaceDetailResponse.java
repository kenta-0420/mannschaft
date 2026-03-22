package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 区画詳細レスポンスDTO。現在の割り当て情報を含む。
 */
@Getter
@RequiredArgsConstructor
public class SpaceDetailResponse {

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
    private final AssignmentResponse currentAssignment;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
