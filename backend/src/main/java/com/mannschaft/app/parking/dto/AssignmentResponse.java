package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 割り当てレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AssignmentResponse {

    private final Long id;
    private final Long spaceId;
    private final Long vehicleId;
    private final Long userId;
    private final Long assignedBy;
    private final LocalDateTime assignedAt;
    private final LocalDate contractStartDate;
    private final LocalDate contractEndDate;
    private final LocalDateTime releasedAt;
    private final Long releasedBy;
    private final String releaseReason;
}
