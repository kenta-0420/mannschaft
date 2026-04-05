package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 申請レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApplicationResponse {

    private final Long id;
    private final Long spaceId;
    private final Long userId;
    private final Long vehicleId;
    private final String sourceType;
    private final Long listingId;
    private final String status;
    private final Integer priority;
    private final String message;
    private final String rejectionReason;
    private final Integer lotteryNumber;
    private final LocalDateTime decidedAt;
    private final LocalDateTime createdAt;
}
