package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 駐車場統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ParkingStatsResponse {

    private final long totalSpaces;
    private final long vacantSpaces;
    private final long occupiedSpaces;
    private final long maintenanceSpaces;
    private final long pendingApplications;
    private final long activeListings;
    private final long activeSubleases;
}
