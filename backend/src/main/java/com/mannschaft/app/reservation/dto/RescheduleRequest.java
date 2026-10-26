package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約リスケジュールリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RescheduleRequest {

    @NotNull
    private final Long newSlotId;
}
