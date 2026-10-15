package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 譲渡希望レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ListingResponse {

    private final Long id;
    private final Long spaceId;
    private final Long assignmentId;
    private final Long listedBy;
    private final String reason;
    private final LocalDate desiredTransferDate;
    private final String status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
