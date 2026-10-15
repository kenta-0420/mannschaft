package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FacilityDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String facilityType;
    private final String facilityTypeLabel;
    private final Integer capacity;
    private final String floor;
    private final String locationDetail;
    private final String description;
    private final List<String> imageUrls;
    private final BigDecimal ratePerSlot;
    private final BigDecimal ratePerNight;
    private final LocalTime checkInTime;
    private final LocalTime checkOutTime;
    private final Integer cleaningBufferMinutes;
    private final Boolean autoApprove;
    private final Boolean isActive;
    private final Integer displayOrder;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
