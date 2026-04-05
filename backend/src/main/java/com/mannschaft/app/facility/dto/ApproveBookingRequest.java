package com.mannschaft.app.facility.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約承認リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApproveBookingRequest {

    @Size(max = 500)
    private final String adminComment;
}
