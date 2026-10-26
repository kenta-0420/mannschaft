package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 貸出レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AssignmentResponse {

    private final Long assignmentId;
    private final Long equipmentItemId;
    private final String equipmentName;
    private final Long assignedToUserId;
    private final String assignedToDisplayName;
    private final Integer quantity;
    private final LocalDateTime assignedAt;
    private final LocalDate expectedReturnAt;
    private final LocalDateTime returnedAt;
    private final String note;
}
