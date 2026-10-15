package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一括返却レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkReturnResponse {

    private final Integer returnedCount;
    private final LocalDateTime returnedAt;
    private final String equipmentStatus;
    private final Integer availableQuantity;
}
