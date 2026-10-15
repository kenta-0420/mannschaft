package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約ライン更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateReservationLineRequest {

    @Size(max = 50)
    private final String name;

    @Size(max = 200)
    private final String description;

    private final Integer displayOrder;

    private final Boolean isActive;

    private final Long defaultStaffUserId;
}
