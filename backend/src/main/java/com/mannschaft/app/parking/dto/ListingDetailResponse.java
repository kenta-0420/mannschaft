package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 譲渡希望詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ListingDetailResponse {

    private final Long id;
    private final Long spaceId;
    private final Long assignmentId;
    private final Long listedBy;
    private final String reason;
    private final LocalDate desiredTransferDate;
    private final String status;
    private final Long transfereeUserId;
    private final Long transfereeVehicleId;
    private final LocalDateTime transferredAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
