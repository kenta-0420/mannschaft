package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 返却レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReturnResponse {

    private final Long assignmentId;
    private final LocalDateTime returnedAt;
    private final String equipmentStatus;
    private final Integer availableQuantity;
}
