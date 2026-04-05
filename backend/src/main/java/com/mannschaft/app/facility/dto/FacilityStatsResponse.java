package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 施設統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FacilityStatsResponse {

    private final Integer totalFacilities;
    private final Integer activeFacilities;
    private final Long totalBookings;
    private final Long completedBookings;
    private final Long cancelledBookings;
    private final Long noShowBookings;
    private final BigDecimal totalRevenue;
    private final BigDecimal totalPlatformFee;
}
