package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFacilityRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotNull
    private final String facilityType;

    @Size(max = 50)
    private final String facilityTypeLabel;

    @NotNull
    @Min(1)
    private final Integer capacity;

    @Size(max = 10)
    private final String floor;

    @Size(max = 200)
    private final String locationDetail;

    private final String description;

    private final List<String> imageUrls;

    private final BigDecimal ratePerSlot;

    private final BigDecimal ratePerNight;

    private final LocalTime checkInTime;

    private final LocalTime checkOutTime;

    @Min(0)
    private final Integer cleaningBufferMinutes;

    private final Boolean autoApprove;

    @Min(0)
    private final Integer displayOrder;
}
