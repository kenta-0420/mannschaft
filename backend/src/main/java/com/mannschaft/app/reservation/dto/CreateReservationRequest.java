package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReservationRequest {

    @NotNull
    private final Long reservationSlotId;

    @NotNull
    private final Long lineId;

    @Size(max = 500)
    private final String userNote;
}
