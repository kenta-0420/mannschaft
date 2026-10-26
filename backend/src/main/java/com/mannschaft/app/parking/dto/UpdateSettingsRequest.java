package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 駐車場設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSettingsRequest {

    @Min(1)
    private final Integer maxSpacesPerUser;

    @Min(0)
    private final Integer maxVisitorReservationsPerDay;

    @Min(1)
    private final Integer visitorReservationMaxDaysAhead;

    private final Boolean visitorReservationRequiresApproval;
}
