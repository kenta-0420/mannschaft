package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 来場者予約レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class VisitorReservationResponse {

    private final Long id;
    private final Long spaceId;
    private final Long reservedBy;
    private final String visitorName;
    private final String visitorPlateNumber;
    private final LocalDate reservedDate;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final String purpose;
    private final String adminComment;
    private final Long approvedBy;
    private final LocalDateTime approvedAt;
    private final String status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
