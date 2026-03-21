package com.mannschaft.app.reservation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 予約ラインレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReservationLineResponse {

    private final Long id;
    private final Long teamId;
    private final String name;
    private final String description;
    private final Integer displayOrder;
    private final Boolean isActive;
    private final Long defaultStaffUserId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
