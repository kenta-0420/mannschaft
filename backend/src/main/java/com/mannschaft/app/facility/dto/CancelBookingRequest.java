package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約キャンセルリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CancelBookingRequest {

    @Size(max = 500)
    private final String cancellationReason;
}
