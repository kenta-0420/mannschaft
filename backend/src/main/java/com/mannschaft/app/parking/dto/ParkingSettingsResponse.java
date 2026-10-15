package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 駐車場設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ParkingSettingsResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Integer maxSpacesPerUser;
    private final Integer maxVisitorReservationsPerDay;
    private final Integer visitorReservationMaxDaysAhead;
    private final Boolean visitorReservationRequiresApproval;
}
