package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FacilityResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String facilityType;
    private final String facilityTypeLabel;
    private final Integer capacity;
    private final String floor;
    private final BigDecimal ratePerSlot;
    private final BigDecimal ratePerNight;
    private final Boolean autoApprove;
    private final Boolean isActive;
    private final Integer displayOrder;
    private final LocalDateTime createdAt;
}
