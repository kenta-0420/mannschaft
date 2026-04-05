package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ブロック時間レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlockedTimeResponse {

    private final Long id;
    private final Long teamId;
    private final LocalDate blockedDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String reason;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
