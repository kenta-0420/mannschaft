package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約キャンセルリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CancelReservationRequest {

    @Size(max = 500)
    private final String reason;
}
