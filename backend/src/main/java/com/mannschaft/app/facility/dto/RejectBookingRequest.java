package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約却下リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RejectBookingRequest {

    @Size(max = 500)
    private final String adminComment;
}
